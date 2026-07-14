package com.tokensea.asset.service;

import com.baomidou.mybatisplus.core.conditions.query.QueryWrapper;
import com.tokensea.asset.entity.ProviderInstance;
import com.tokensea.provider.entity.ProviderSecret;
import com.tokensea.provider.mapper.ProviderSecretMapper;
import com.tokensea.provider.service.CryptoService;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.stereotype.Service;

import java.io.InputStream;
import java.net.InetSocketAddress;
import java.net.ProxySelector;
import java.net.URI;
import java.net.http.HttpClient;
import java.net.http.HttpRequest;
import java.net.http.HttpResponse;
import java.nio.charset.StandardCharsets;
import java.time.Duration;
import java.util.Arrays;
import java.util.Locale;
import java.util.Set;
import java.util.stream.Collectors;

@Service
public class ProviderConnectionService {
    static final int MAX_RESPONSE_BYTES=64*1024;
    static final int MAX_DISCOVERY_BYTES=1024*1024;
    private final ProviderSecretMapper secrets;
    private final CryptoService crypto;
    private final Set<String> allowedHosts;
    private final Set<Integer> allowedPorts;
    private final HttpClient client;

    public ProviderConnectionService(ProviderSecretMapper secrets, CryptoService crypto,
            @Value("${tokensea.egress.allowed-hosts:}") String hosts,
            @Value("${tokensea.egress.allowed-ports:80,443}") String ports,
            @Value("${tokensea.egress.proxy-host:}") String proxyHost,
            @Value("${tokensea.egress.proxy-port:18080}") int proxyPort) {
        this.secrets=secrets; this.crypto=crypto;
        this.allowedHosts=Arrays.stream(hosts.split(",")).map(v->v.trim().toLowerCase(Locale.ROOT)).filter(v->!v.isBlank()).collect(Collectors.toUnmodifiableSet());
        try { this.allowedPorts=Arrays.stream(ports.split(",")).map(String::trim).filter(v->!v.isBlank()).map(Integer::valueOf).collect(Collectors.toUnmodifiableSet()); }
        catch(Exception e){throw new IllegalArgumentException("TOKENSEA_EGRESS_ALLOWED_PORTS 配置无效");}
        if(proxyHost==null||proxyHost.isBlank()||proxyPort<1||proxyPort>65535) throw new IllegalArgumentException("供应商出口代理未配置");
        this.client=HttpClient.newBuilder().proxy(ProxySelector.of(new InetSocketAddress(proxyHost,proxyPort)))
                .connectTimeout(Duration.ofSeconds(5)).followRedirects(HttpClient.Redirect.NEVER).build();
    }

    public TestResult test(ProviderInstance instance){
        Target target;
        try{target=target(instance.getApiBase());}catch(Exception e){return TestResult.failed("PROVIDER_TARGET_NOT_ALLOWED","供应商目标未列入出口白名单");}
        String apiKey;
        try{apiKey=resolveApiKey(instance);}catch(Exception e){return TestResult.failed("PROVIDER_CREDENTIAL_INVALID","供应商密钥未配置或归属无效");}
        if(!"无需 Key".equals(instance.getKeyStatus())&&(apiKey==null||apiKey.isBlank())) return TestResult.failed("PROVIDER_CREDENTIAL_MISSING","供应商密钥未配置");
        try{
            HttpRequest.Builder request=HttpRequest.newBuilder(target.uri()).GET().timeout(Duration.ofSeconds(10)).header("Accept","application/json");
            applyAuthentication(request,instance.getApiStyle(),apiKey);
            HttpResponse<InputStream> response=client.send(request.build(),HttpResponse.BodyHandlers.ofInputStream());
            try(InputStream body=response.body()){if(body.readNBytes(MAX_RESPONSE_BYTES+1).length>MAX_RESPONSE_BYTES)return TestResult.failed("PROVIDER_RESPONSE_TOO_LARGE","供应商响应超过安全限制");}
            if(response.statusCode()>=200&&response.statusCode()<300)return TestResult.success(response.statusCode(),target);
            if(response.statusCode()==403)return TestResult.failed("PROVIDER_EGRESS_DENIED","出口代理拒绝该供应商目标");
            if(response.statusCode()>=300&&response.statusCode()<400)return TestResult.failed("PROVIDER_REDIRECT_REJECTED","供应商返回重定向，连接测试已拒绝");
            return TestResult.failed("PROVIDER_UPSTREAM_ERROR","供应商连接测试失败，HTTP "+response.statusCode());
        }catch(InterruptedException e){Thread.currentThread().interrupt();return TestResult.failed("PROVIDER_TEST_INTERRUPTED","供应商连接测试被中断");}
        catch(java.net.http.HttpTimeoutException e){return TestResult.failed("PROVIDER_EGRESS_TIMEOUT","供应商出口代理请求超时");}
        catch(Exception e){return TestResult.failed("PROVIDER_EGRESS_UNAVAILABLE","供应商出口代理不可用");}
    }

    public DiscoveryResult discoverModels(ProviderInstance instance){
        Target target;
        try{target=target(instance.getApiBase());}catch(Exception e){return DiscoveryResult.failed("PROVIDER_TARGET_NOT_ALLOWED","供应商目标未列入出口白名单");}
        String apiKey;
        try{apiKey=resolveApiKey(instance);}catch(Exception e){return DiscoveryResult.failed("PROVIDER_CREDENTIAL_INVALID","供应商密钥未配置或归属无效");}
        if(!"无需 Key".equals(instance.getKeyStatus())&&(apiKey==null||apiKey.isBlank())) return DiscoveryResult.failed("PROVIDER_CREDENTIAL_MISSING","供应商密钥未配置");
        try{
            HttpRequest.Builder request=HttpRequest.newBuilder(target.uri()).GET().timeout(Duration.ofSeconds(20)).header("Accept","application/json");
            applyAuthentication(request,instance.getApiStyle(),apiKey);
            HttpResponse<InputStream> response=client.send(request.build(),HttpResponse.BodyHandlers.ofInputStream());
            byte[] bytes;
            try(InputStream body=response.body()){bytes=body.readNBytes(MAX_DISCOVERY_BYTES+1);}
            if(bytes.length>MAX_DISCOVERY_BYTES)return DiscoveryResult.failed("PROVIDER_RESPONSE_TOO_LARGE","供应商模型列表超过安全限制");
            if(response.statusCode()==403)return DiscoveryResult.failed("PROVIDER_EGRESS_DENIED","出口代理拒绝该供应商目标");
            if(response.statusCode()<200||response.statusCode()>=300)return DiscoveryResult.failed("PROVIDER_UPSTREAM_ERROR","供应商模型发现失败，HTTP "+response.statusCode());
            return new DiscoveryResult(true,response.statusCode(),null,null,target.uri().toString(),target.host(),target.port(),new String(bytes,StandardCharsets.UTF_8));
        }catch(InterruptedException e){Thread.currentThread().interrupt();return DiscoveryResult.failed("PROVIDER_DISCOVERY_INTERRUPTED","供应商模型发现被中断");}
        catch(java.net.http.HttpTimeoutException e){return DiscoveryResult.failed("PROVIDER_EGRESS_TIMEOUT","供应商出口代理请求超时");}
        catch(Exception e){return DiscoveryResult.failed("PROVIDER_EGRESS_UNAVAILABLE","供应商出口代理不可用");}
    }

    public CapabilityProbeResult probeCapability(ProviderInstance instance,String model,String capability){
        Target modelsTarget;
        try{modelsTarget=target(instance.getApiBase());}catch(Exception e){return CapabilityProbeResult.failed("PROVIDER_TARGET_NOT_ALLOWED","供应商目标未列入出口白名单");}
        String apiKey;
        try{apiKey=resolveApiKey(instance);}catch(Exception e){return CapabilityProbeResult.failed("PROVIDER_CREDENTIAL_INVALID","供应商密钥未配置或归属无效");}
        if(!"无需 Key".equals(instance.getKeyStatus())&&(apiKey==null||apiKey.isBlank()))return CapabilityProbeResult.failed("PROVIDER_CREDENTIAL_MISSING","供应商密钥未配置");
        String type=capability==null?"":capability.toUpperCase(Locale.ROOT),suffix,body;
        boolean streaming="STREAM".equals(type);
        if("EMBEDDING".equals(type)){suffix="/embeddings";body="{\"model\":\""+escape(model)+"\",\"input\":\"tokensea capability probe\"}";}
        else if("CHAT".equals(type)||streaming){suffix="/chat/completions";body="{\"model\":\""+escape(model)+"\",\"messages\":[{\"role\":\"user\",\"content\":\"Reply with OK\"}],\"max_tokens\":4,\"stream\":"+streaming+"}";}
        else return CapabilityProbeResult.failed("CAPABILITY_NOT_SUPPORTED","仅支持 CHAT、STREAM、EMBEDDING 探测");
        URI endpoint=replaceModelsPath(modelsTarget.uri(),suffix);
        try{HttpRequest.Builder request=HttpRequest.newBuilder(endpoint).timeout(Duration.ofSeconds(30)).header("Accept",streaming?"text/event-stream":"application/json").header("Content-Type","application/json").POST(HttpRequest.BodyPublishers.ofString(body));applyAuthentication(request,instance.getApiStyle(),apiKey);long started=System.nanoTime();HttpResponse<InputStream> response=client.send(request.build(),HttpResponse.BodyHandlers.ofInputStream());byte[] bytes;try(InputStream input=response.body()){bytes=input.readNBytes(MAX_RESPONSE_BYTES+1);}int latency=(int)((System.nanoTime()-started)/1_000_000);if(bytes.length>MAX_RESPONSE_BYTES)return CapabilityProbeResult.failed("PROVIDER_RESPONSE_TOO_LARGE","能力探测响应超过安全限制");boolean streamVerified=!streaming||new String(bytes,StandardCharsets.UTF_8).contains("data:");boolean success=response.statusCode()>=200&&response.statusCode()<300&&streamVerified;return new CapabilityProbeResult(success,response.statusCode(),success?null:"CAPABILITY_PROBE_FAILED",success?null:"能力探测未通过",endpoint.toString(),latency,streamVerified,bytes.length);
        }catch(InterruptedException e){Thread.currentThread().interrupt();return CapabilityProbeResult.failed("CAPABILITY_PROBE_INTERRUPTED","能力探测被中断");}catch(Exception e){return CapabilityProbeResult.failed("CAPABILITY_PROBE_UNAVAILABLE","能力探测请求失败");}
    }

    public boolean matchesVerifiedTarget(ProviderInstance instance){
        try{Target current=target(instance.getApiBase());return current.host().equals(instance.getLastConnectionTestHost())&&current.port()==instance.getLastConnectionTestPort();}
        catch(Exception e){return false;}
    }
    Target target(String baseValue){
        if(baseValue==null||baseValue.isBlank())throw new IllegalArgumentException();
        URI base=URI.create(baseValue.trim());
        if(!("http".equalsIgnoreCase(base.getScheme())||"https".equalsIgnoreCase(base.getScheme()))||base.getHost()==null||base.getUserInfo()!=null||base.getFragment()!=null)throw new IllegalArgumentException();
        String host=base.getHost().toLowerCase(Locale.ROOT);int port=base.getPort()>0?base.getPort():("https".equalsIgnoreCase(base.getScheme())?443:80);
        if(!allowedHosts.contains(host)||!allowedPorts.contains(port))throw new IllegalArgumentException();
        return new Target(URI.create(baseValue.replaceAll("/+$","")+"/models"),host,port);
    }
    public String resolveManagedApiKey(ProviderInstance instance){return resolveApiKey(instance);}
    public void applyManagedAuthentication(HttpRequest.Builder request,ProviderInstance instance,String apiKey){applyAuthentication(request,instance.getApiStyle(),apiKey);}
    private String resolveApiKey(ProviderInstance instance){
        ProviderSecret secret=null;String ref=instance.getCredentialRef();
        if(ref!=null&&!ref.isBlank()){String id=ref.startsWith("secret:")?ref.substring(7):ref;secret=secrets.selectById(id);if(secret==null||!instance.getId().equals(secret.getProviderInstanceId())||!"ACTIVE".equals(secret.getStatus()))throw new IllegalStateException();}
        if(secret==null)secret=secrets.selectOne(new QueryWrapper<ProviderSecret>().eq("provider_instance_id",instance.getId()).eq("secret_name","api_key").eq("status","ACTIVE").orderByDesc("created_at").last("limit 1"));
        return secret==null?null:crypto.decrypt(secret.getSecretCipher());
    }
    private static void applyAuthentication(HttpRequest.Builder request,String apiStyle,String apiKey){if(apiKey==null||apiKey.isBlank())return;String style=apiStyle==null?"":apiStyle.toLowerCase(Locale.ROOT);if(style.contains("azure"))request.header("api-key",apiKey);else if(style.contains("gemini"))request.header("x-goog-api-key",apiKey);else if(style.contains("anthropic"))request.header("x-api-key",apiKey).header("anthropic-version","2023-06-01");else request.header("Authorization","Bearer "+apiKey);}
    record Target(URI uri,String host,int port){}
    public record TestResult(boolean success,Integer httpStatus,String errorCode,String error,String targetHost,Integer targetPort){
        static TestResult success(int status,Target target){return new TestResult(true,status,null,null,target.host(),target.port());}
        static TestResult failed(String code,String message){return new TestResult(false,null,code,message,null,null);}
    }
    public record DiscoveryResult(boolean success,Integer httpStatus,String errorCode,String error,String sourceEndpoint,String targetHost,Integer targetPort,String rawPayload){
        static DiscoveryResult failed(String code,String message){return new DiscoveryResult(false,null,code,message,null,null,null,null);}
    }
    public record CapabilityProbeResult(boolean success,Integer httpStatus,String errorCode,String error,String endpoint,Integer latencyMs,boolean streamVerified,Integer responseBytes){static CapabilityProbeResult failed(String code,String message){return new CapabilityProbeResult(false,null,code,message,null,null,false,null);}}
    private static URI replaceModelsPath(URI models,String suffix){String value=models.toString();return URI.create(value.endsWith("/models")?value.substring(0,value.length()-7)+suffix:value+suffix);}
    private static String escape(String value){return value==null?"":value.replace("\\","\\\\").replace("\"","\\\"");}
}
