package com.tokensea.provider.controller;

import com.tokensea.common.ApiResponse;
import com.tokensea.provider.entity.ProviderSecret;
import com.tokensea.provider.mapper.ProviderSecretMapper;
import com.tokensea.provider.service.CryptoService;
import jakarta.validation.constraints.NotBlank;
import org.springframework.web.bind.annotation.*;
import java.util.List;

@RestController
@RequestMapping("/api/provider-secrets")
public class ProviderSecretController {
    private final ProviderSecretMapper mapper;
    private final CryptoService crypto;
    public ProviderSecretController(ProviderSecretMapper mapper, CryptoService crypto) { this.mapper = mapper; this.crypto = crypto; }
    public record SecretRequest(@NotBlank String providerId, String secretName, @NotBlank String secretValue) {}
    @GetMapping public ApiResponse<List<ProviderSecret>> list(){ return ApiResponse.ok(mapper.selectList(null)); }
    @PostMapping public ApiResponse<ProviderSecret> create(@RequestBody SecretRequest req) {
        ProviderSecret s = new ProviderSecret();
        s.setProviderId(req.providerId());
        s.setSecretName(req.secretName()==null?"api_key":req.secretName());
        s.setSecretCipher(crypto.encrypt(req.secretValue()));
        s.setSecretLast4(req.secretValue().length() <= 4 ? req.secretValue() : req.secretValue().substring(req.secretValue().length()-4));
        s.setStatus("ACTIVE");
        mapper.insert(s);
        s.setSecretCipher("***");
        return ApiResponse.ok(s);
    }
}
