import importlib.util
import json
import pathlib
import sys
import tempfile
import time
import types
import unittest
from decimal import Decimal
from datetime import date, datetime, timezone

from fastapi import HTTPException
from fastapi.testclient import TestClient

# The unit tests exercise pure routing/scope rules and do not require live DB/Redis drivers.
if "asyncpg" not in sys.modules:
    asyncpg = types.ModuleType("asyncpg")
    asyncpg.Pool = object
    sys.modules["asyncpg"] = asyncpg
if "redis.asyncio" not in sys.modules:
    redis_package = types.ModuleType("redis")
    redis_asyncio = types.ModuleType("redis.asyncio")
    redis_asyncio.Redis = object
    redis_package.asyncio = redis_asyncio
    sys.modules["redis"] = redis_package
    sys.modules["redis.asyncio"] = redis_asyncio
if "prometheus_client" not in sys.modules:
    prometheus = types.ModuleType("prometheus_client")
    class Metric:
        def __init__(self, *args, **kwargs): pass
        def labels(self, *args, **kwargs): return self
        def inc(self, *args, **kwargs): pass
        def observe(self, *args, **kwargs): pass
    prometheus.Counter = Metric
    prometheus.Histogram = Metric
    prometheus.generate_latest = lambda: b""
    prometheus.CONTENT_TYPE_LATEST = "text/plain"
    sys.modules["prometheus_client"] = prometheus

MODULE_PATH = pathlib.Path(__file__).parents[1] / "app" / "main.py"
SPEC = importlib.util.spec_from_file_location("tokensea_gateway_main", MODULE_PATH)
gateway = importlib.util.module_from_spec(SPEC)
SPEC.loader.exec_module(gateway)


def token_price_components(input_price="0", output_price="0", cache_read_price=None,
                           cache_write_price=None, cache_read_mode="NOT_APPLICABLE",
                           cache_write_mode="NOT_APPLICABLE"):
    return [
        {"componentType": "INPUT_TOKEN", "variant": "DEFAULT", "unitPrice": input_price,
         "unitBasis": "TOKEN", "unitQuantity": 1_000_000, "mode": "EXPLICIT",
         "scope": {}, "priority": 100},
        {"componentType": "CACHE_READ_TOKEN", "variant": "DEFAULT", "unitPrice": cache_read_price,
         "unitBasis": "TOKEN", "unitQuantity": 1_000_000, "mode": cache_read_mode,
         "scope": {}, "priority": 100},
        {"componentType": "CACHE_WRITE_TOKEN", "variant": "DEFAULT", "unitPrice": cache_write_price,
         "unitBasis": "TOKEN", "unitQuantity": 1_000_000, "mode": cache_write_mode,
         "scope": {}, "priority": 100},
        {"componentType": "OUTPUT_TOKEN", "variant": "DEFAULT", "unitPrice": output_price,
         "unitBasis": "TOKEN", "unitQuantity": 1_000_000, "mode": "EXPLICIT",
         "scope": {}, "priority": 100},
    ]


def component_by_type(components, component_type):
    return next(item for item in components if item["componentType"] == component_type)


class RuntimeRulesTest(unittest.TestCase):
    def test_gateway_cors_allows_console_health_checks(self):
        client = TestClient(gateway.app)
        response = client.get("/health", headers={"Origin": "http://localhost:39210"})
        self.assertEqual(200, response.status_code)
        self.assertEqual("http://localhost:39210", response.headers.get("access-control-allow-origin"))

        preflight = client.options(
            "/health/readiness",
            headers={
                "Origin": "http://localhost:39210",
                "Access-Control-Request-Method": "GET",
            },
        )
        self.assertEqual(200, preflight.status_code)
        self.assertEqual("http://localhost:39210", preflight.headers.get("access-control-allow-origin"))

    def test_gateway_cors_supports_compose_environment_name(self):
        original_primary = gateway.os.environ.get("TOKENSEA_CORS_ORIGINS")
        original_compatible = gateway.os.environ.get("TOKENSEA_CORS_ALLOWED_ORIGINS")
        try:
            gateway.os.environ["TOKENSEA_CORS_ORIGINS"] = ""
            gateway.os.environ["TOKENSEA_CORS_ALLOWED_ORIGINS"] = "http://console.example"
            self.assertEqual(["http://console.example"], gateway.configured_cors_origins())
        finally:
            if original_primary is None:
                gateway.os.environ.pop("TOKENSEA_CORS_ORIGINS", None)
            else:
                gateway.os.environ["TOKENSEA_CORS_ORIGINS"] = original_primary
            if original_compatible is None:
                gateway.os.environ.pop("TOKENSEA_CORS_ALLOWED_ORIGINS", None)
            else:
                gateway.os.environ["TOKENSEA_CORS_ALLOWED_ORIGINS"] = original_compatible

    def test_runtime_pricing_alert_is_persisted_with_deterministic_identity(self):
        class CapturingPool:
            def __init__(self):
                self.calls = []

            async def execute(self, query, *args):
                self.calls.append((query, args))

        pool = CapturingPool()
        previous_pool = gateway.pool
        gateway.pool = pool
        try:
            error = HTTPException(status_code=409, detail={
                "error_code": "TOKENSEA_CACHE_PRICE_MISSING", "message": "缓存价格缺失"})
            route = {"provider_id": "provider-it", "provider_name": "DeepSeek",
                     "runtime_model_name": "deepseek-v4-pro", "price": {"id": "price-it"}}
            import asyncio
            asyncio.run(gateway.record_runtime_pricing_alert("request-it", route, error))
        finally:
            gateway.pool = previous_pool

        self.assertEqual(1, len(pool.calls))
        query, args = pool.calls[0]
        self.assertIn("INSERT INTO alert_event", query)
        self.assertIn("ON CONFLICT(id) DO UPDATE", query)
        self.assertEqual("CACHE_PRICE_MISSING", args[1])
        self.assertEqual("request-it", args[2])
        detail = gateway.json.loads(args[4])
        self.assertEqual("TOKENSEA_CACHE_PRICE_MISSING", detail["errorCode"])
        self.assertEqual("price-it", detail["priceVersionId"])

    def test_scope_requires_non_empty_json_array(self):
        for invalid in (None, "", "[]", "{}", "not-json", '["", "chat"]'):
            with self.subTest(invalid=invalid), self.assertRaises(HTTPException):
                gateway.parse_scope(invalid)

    def test_scope_is_deduplicated(self):
        self.assertEqual(["chat-standard"], gateway.parse_scope('["chat-standard", "chat-standard"]'))

    def test_effective_model_scope_is_tenant_and_key_intersection(self):
        context = {
            "model_scope_parsed": ["chat-standard", "kimi-enterprise"],
            "tenant_scope_parsed": ["chat-standard"],
        }
        gateway.validate_model_scope(context, "chat-standard")
        with self.assertRaises(HTTPException) as denied:
            gateway.validate_model_scope(context, "kimi-enterprise")
        self.assertEqual(403, denied.exception.status_code)
        self.assertEqual("TOKENSEA_MODEL_FORBIDDEN", denied.exception.detail["error_code"])

    def test_runtime_model_uses_provider_adapter(self):
        self.assertEqual("anthropic/claude-test", gateway.runtime_model_name({"api_style": "anthropic"}, "claude-test"))
        self.assertEqual("openai/qwen-test", gateway.runtime_model_name({"api_style": "openai_compatible"}, "qwen-test"))
        self.assertEqual("openrouter/model", gateway.runtime_model_name({"api_style": "openai_compatible"}, "openrouter/model"))

    def test_provider_requires_real_connection_result(self):
        configured = {"api_base": "https://provider.example/v1", "status": "启用", "health_status": "健康", "last_connection_test_status": "成功", "last_connection_test_at": datetime.now(timezone.utc), "last_connection_test_host": "provider.example", "last_connection_test_addresses": "8.8.8.8", "key_status": "已托管"}
        self.assertTrue(gateway.provider_is_routable(configured))
        configured["last_connection_test_at"] = datetime.fromtimestamp(
            time.time() - gateway.CONNECTION_TEST_MAX_AGE_SECONDS + 60, timezone.utc
        )
        self.assertTrue(gateway.provider_is_routable(configured))
        configured["last_connection_test_at"] = datetime.fromtimestamp(
            time.time() - gateway.CONNECTION_TEST_MAX_AGE_SECONDS - 60, timezone.utc
        )
        self.assertFalse(gateway.provider_is_routable(configured))
        configured["last_connection_test_at"] = datetime.now(timezone.utc)
        configured["last_connection_test_status"] = "失败"
        self.assertFalse(gateway.provider_is_routable(configured))

    def test_ip_whitelist_supports_cidr_and_fails_closed(self):
        gateway.validate_ip_whitelist('["10.0.0.0/8"]', "10.2.3.4")
        with self.assertRaises(HTTPException):
            gateway.validate_ip_whitelist('["10.0.0.0/8"]', "192.168.1.2")
        with self.assertRaises(HTTPException):
            gateway.validate_ip_whitelist("not-json", "10.2.3.4")

    def test_visibility_and_price_calculation(self):
        gateway.validate_visibility("全部租户", {"tenant_id": "t1", "tenant_type": "INTERNAL"})
        gateway.validate_visibility('["全部租户"]', {"tenant_id": "t1", "tenant_type": "INTERNAL"})
        gateway.validate_visibility('["t1"]', {"tenant_id": "t1", "tenant_type": "INTERNAL"})
        with self.assertRaises(HTTPException):
            gateway.validate_visibility('["t2"]', {"tenant_id": "t1", "tenant_type": "INTERNAL"})
        usage = {"prompt_tokens": 1000, "completion_tokens": 500, "total_tokens": 1500}
        cost, sales, components, metrics = gateway.calculate_amounts({
            "cost_billing_basis": "TOKEN", "cost_billing_quantity": 1_000_000,
            "input_cost_unit_price": "1000", "output_cost_unit_price": "2000",
            "price_billing_basis": "TOKEN", "price_billing_quantity": 1_000_000,
            "input_price_unit_price": "3000", "output_price_unit_price": "4000",
            "price_components": token_price_components("1000", "2000"),
            "internal_price_id": "internal-1"
        }, usage)
        self.assertEqual(gateway.Decimal("2"), cost)
        self.assertEqual(gateway.Decimal("5"), sales)
        input_component = component_by_type(components, "INPUT_TOKEN")
        self.assertEqual("1", input_component["amount"])
        self.assertEqual("1000", input_component["usageQuantity"])
        self.assertEqual("0", metrics["cacheNetSavings"])
        zero_cost, zero_sales, _, _ = gateway.calculate_amounts({
            "cost_billing_basis": "TOKEN", "cost_billing_quantity": 1_000_000,
            "input_cost_unit_price": "0", "output_cost_unit_price": "0",
            "price_billing_basis": "TOKEN", "price_billing_quantity": 1_000_000,
            "input_price_unit_price": "0", "output_price_unit_price": "0",
            "price_components": token_price_components("0", "0")
        }, usage)
        self.assertEqual(Decimal("0"), zero_cost)
        self.assertEqual(Decimal("0"), zero_sales)

    def test_cache_tokens_use_component_price_without_double_counting(self):
        usage = gateway.normalize_usage({
            "prompt_tokens": 1000,
            "completion_tokens": 100,
            "prompt_tokens_details": {"cached_tokens": 400},
        })
        cost, sales, components, metrics = gateway.calculate_amounts({
            "cost_billing_basis": "TOKEN", "cost_billing_quantity": 1_000_000,
            "input_cost_unit_price": "1000", "output_cost_unit_price": "2000",
            "price_billing_basis": "TOKEN", "price_billing_quantity": 1_000_000,
            "input_price_unit_price": "1000", "output_price_unit_price": "2000",
            "price_components": token_price_components("1000", "2000", "100", None,
                                                        "EXPLICIT", "NOT_APPLICABLE"),
        }, usage)
        self.assertEqual(Decimal("0.84"), cost)
        self.assertEqual(cost, sales)
        self.assertEqual("0.04", component_by_type(components, "CACHE_READ_TOKEN")["amount"])
        self.assertEqual("0.36", metrics["cacheGrossSavings"])
        self.assertEqual("0.36", metrics["cacheNetSavings"])

    def test_provider_usage_adapters_produce_mutually_exclusive_token_counts(self):
        cases = [
            ("deepseek", {"prompt_tokens": 1000, "completion_tokens": 100, "total_tokens": 1100,
                          "prompt_cache_hit_tokens": 400, "prompt_cache_miss_tokens": 600},
             (600, 400, 0, "DEEPSEEK")),
            ("anthropic", {"input_tokens": 600, "output_tokens": 100,
                           "cache_read_input_tokens": 300, "cache_creation_input_tokens": 100},
             (600, 300, 100, "ANTHROPIC")),
            ("openai", {"prompt_tokens": 1000, "completion_tokens": 100,
                        "prompt_tokens_details": {"cached_tokens": 250}},
             (750, 250, 0, "OPENAI")),
            ("gemini", {"promptTokenCount": 1000, "candidatesTokenCount": 100,
                        "cachedContentTokenCount": 200, "totalTokenCount": 1100},
             (800, 200, 0, "GEMINI")),
        ]
        for provider, raw, expected in cases:
            with self.subTest(provider=provider):
                usage = gateway.normalize_usage(raw, provider)
                self.assertEqual(expected[0], usage["input_uncached_tokens"])
                self.assertEqual(expected[1], usage["cache_read_tokens"])
                self.assertEqual(expected[2], usage["cache_write_tokens"])
                self.assertEqual(expected[3], usage["usage_source"])
                self.assertEqual(usage["input_tokens_total"],
                                 usage["input_uncached_tokens"] + usage["cache_read_tokens"]
                                 + usage["cache_write_tokens"])
                self.assertEqual("COMPLETE", usage["cost_status"])

    def test_unknown_cache_price_is_not_silently_treated_as_zero(self):
        usage = gateway.normalize_usage({"prompt_tokens": 1000, "completion_tokens": 100,
                                         "prompt_tokens_details": {"cached_tokens": 400}}, "openai")
        with self.assertRaises(HTTPException) as error:
            gateway.calculate_amounts({
                "cost_billing_basis": "TOKEN", "cost_billing_quantity": 1_000_000,
                "input_cost_unit_price": "1000", "output_cost_unit_price": "2000",
                "price_components": token_price_components("1000", "2000", None, None,
                                                            "UNKNOWN", "NOT_APPLICABLE"),
            }, usage)
        self.assertEqual("TOKENSEA_CACHE_PRICE_MISSING", error.exception.detail["error_code"])

    def test_long_context_cache_variant_is_selected_by_scope(self):
        usage = gateway.normalize_usage({
            "usage_schema_version": 2, "input_uncached_tokens": 210000,
            "input_tokens_total": 210100, "cache_read_tokens": 100, "cache_write_tokens": 0,
            "completion_tokens": 10, "output_tokens": 10, "reasoning_tokens": 0,
            "total_tokens": 210110,
        })
        components = token_price_components("10", "20", "1", None, "EXPLICIT", "NOT_APPLICABLE")
        components.append({"componentType": "CACHE_READ_TOKEN", "variant": "ABOVE_200K",
                           "unitPrice": "2", "unitBasis": "TOKEN", "unitQuantity": 1_000_000,
                           "mode": "EXPLICIT", "scope": {"minContextTokens": 200000}, "priority": 50})
        _, _, cost_components, _ = gateway.calculate_amounts({
            "cost_billing_basis": "TOKEN", "cost_billing_quantity": 1_000_000,
            "input_cost_unit_price": "10", "output_cost_unit_price": "20",
            "price_components": components,
        }, usage)
        cache_component = component_by_type(cost_components, "CACHE_READ_TOKEN")
        self.assertEqual("ABOVE_200K", cache_component["variant"])
        self.assertEqual("2", cache_component["unitPrice"])

    def test_inconsistent_deepseek_usage_is_rejected(self):
        usage = gateway.normalize_usage({"prompt_tokens": 1000, "completion_tokens": 100,
                                         "prompt_cache_hit_tokens": 400,
                                         "prompt_cache_miss_tokens": 500}, "deepseek")
        self.assertEqual("INVALID_USAGE", usage["cost_status"])
        with self.assertRaises(HTTPException) as error:
            gateway.calculate_amounts({
                "price_components": token_price_components("1000", "2000", "100", None,
                                                            "EXPLICIT", "NOT_APPLICABLE")
            }, usage)
        self.assertEqual("TOKENSEA_CACHE_USAGE_INCONSISTENT", error.exception.detail["error_code"])

    def test_generic_request_component_uses_configured_quantity(self):
        usage = {"prompt_tokens": 0, "completion_tokens": 0, "total_tokens": 0}
        cost, sales, components, _ = gateway.calculate_amounts({
            "cost_billing_basis": "REQUEST", "cost_billing_quantity": 100,
            "input_cost_unit_price": "0", "output_cost_unit_price": "0",
            "price_components": [
                {"componentType": "REQUEST", "variant": "DEFAULT", "unitPrice": "5",
                 "unitBasis": "REQUEST", "unitQuantity": 100, "mode": "EXPLICIT",
                 "scope": {}, "priority": 100},
            ],
        }, usage)
        self.assertEqual(Decimal("0.05"), cost)
        self.assertEqual(cost, sales)
        request_component = component_by_type(components, "REQUEST")
        self.assertEqual("0.05", request_component["amount"])
        self.assertEqual("1", request_component["usageQuantity"])

    def test_generic_image_component_preserves_non_token_usage(self):
        usage = {"prompt_tokens": 0, "completion_tokens": 0, "total_tokens": 0, "output_images": 2}
        cost, sales, components, _ = gateway.calculate_amounts({
            "cost_billing_basis": "IMAGE", "cost_billing_quantity": 1,
            "input_cost_unit_price": "0", "output_cost_unit_price": "3",
            "price_components": [
                {"componentType": "IMAGE_OUTPUT", "variant": "DEFAULT", "unitPrice": "3",
                 "unitBasis": "IMAGE", "unitQuantity": 1, "mode": "EXPLICIT",
                 "scope": {}, "priority": 100},
            ],
        }, usage)
        self.assertEqual(Decimal("6"), cost)
        self.assertEqual(cost, sales)
        image_component = component_by_type(components, "IMAGE_OUTPUT")
        self.assertEqual("6", image_component["amount"])
        self.assertEqual("2", image_component["usageQuantity"])

    def test_java_crypto_fixed_vectors_v2_and_legacy(self):
        previous = gateway.CRYPTO_KEY
        gateway.CRYPTO_KEY = "AAECAwQFBgcICQoLDA0ODxAREhMUFRYXGBkaGxwdHh8="
        try:
            self.assertEqual("TokenSea跨语言密钥", gateway.decrypt_secret(
                "v2.AAECAwQFBgcICQoL.E229fqu2p3pl9j9jHkSQxQMzKLIZ6fpBpKT5jhZqmhFJIExNu6ey"))
            self.assertEqual("TokenSea跨语言密钥", gateway.decrypt_secret(
                "AAECAwQFBgcICQoL.anSDTzvCaX4Q+AeK4aeM+Id6fAhJB1e337Rv+RwQDRGcSvnGAGtk"))
            with self.assertRaises(HTTPException):
                gateway.decrypt_secret("v2.invalid.invalid")
        finally:
            gateway.CRYPTO_KEY = previous

    def test_split_database_config_and_request_id_validation(self):
        previous = (gateway.DB_DSN, gateway.DB_HOST, gateway.DB_PORT, gateway.DB_NAME,
                    gateway.DB_USER, gateway.DB_PASSWORD)
        gateway.DB_DSN = None
        gateway.DB_HOST, gateway.DB_PORT = "postgres", 5432
        gateway.DB_NAME, gateway.DB_USER, gateway.DB_PASSWORD = "db", "user", "password"
        try:
            config = gateway.database_pool_kwargs()
            self.assertEqual("postgres", config["host"])
            self.assertEqual("db", config["database"])
            self.assertNotIn("dsn", config)
        finally:
            (gateway.DB_DSN, gateway.DB_HOST, gateway.DB_PORT, gateway.DB_NAME,
             gateway.DB_USER, gateway.DB_PASSWORD) = previous
        gateway.validate_client_request_id("client.req-1:retry")
        for invalid in ("", " has-space", "x" * 65, "包含中文"):
            with self.subTest(invalid=invalid), self.assertRaises(HTTPException):
                gateway.validate_client_request_id(invalid)


class GatewayAsyncRulesTest(unittest.IsolatedAsyncioTestCase):
    async def test_explicit_local_test_upstream_accepts_private_snapshot_only_for_exact_host(self):
        previous_enabled, previous_hosts = gateway.LOCAL_TEST_UPSTREAM_ENABLED, gateway.LOCAL_TEST_UPSTREAM_HOSTS
        gateway.LOCAL_TEST_UPSTREAM_ENABLED = True
        gateway.LOCAL_TEST_UPSTREAM_HOSTS = {"host.docker.internal"}
        route = {
            "api_base": "http://host.docker.internal:39300/v1",
            "verified_host": "host.docker.internal",
            "verified_addresses": "127.0.0.1",
        }
        try:
            await gateway.validate_route_dns(route, force=True)
            self.assertGreater(route.get("dns_valid_until", 0), time.monotonic())
            with self.assertRaises(HTTPException):
                gateway.validate_public_addresses({"127.0.0.1"})
            self.assertFalse(gateway.is_local_test_upstream("other.internal"))
        finally:
            gateway.LOCAL_TEST_UPSTREAM_ENABLED = previous_enabled
            gateway.LOCAL_TEST_UPSTREAM_HOSTS = previous_hosts

    async def test_runtime_model_exists_supports_model_info_id_and_search_fallback(self):
        class Response:
            status_code = 200
            def __init__(self, payload): self.payload = payload
            def json(self): return self.payload

        class Client:
            def __init__(self): self.calls = []
            async def get(self, _url, **kwargs):
                params = kwargs.get("params", {})
                self.calls.append(params)
                if "modelId" in params:
                    return Response({"data": [], "total_pages": 1})
                return Response({"data": [{"model_name": "other", "model_info": {"id": "ts-existing"}}], "total_pages": 1})

        client = Client()
        self.assertTrue(await gateway.runtime_model_exists(client, "ts-existing"))
        self.assertEqual("ts-existing", client.calls[0]["modelId"])
        self.assertEqual("ts-existing", client.calls[1]["search"])

    async def test_runtime_registration_reuses_persisted_model_after_gateway_restart(self):
        class Response:
            status_code = 500

        class Client:
            async def __aenter__(self): return self
            async def __aexit__(self, *_): return False
            async def post(self, *_args, **_kwargs): return Response()

        async def persisted(_client, _alias): return True
        async def skip_dns(_route, force=False): return None

        previous_client = gateway.httpx.AsyncClient
        previous_exists = gateway.runtime_model_exists
        client_kwargs = {}
        previous_dns = gateway.validate_route_dns
        previous_models = dict(gateway.runtime_models)
        def client_factory(*args, **kwargs):
            client_kwargs.update(kwargs)
            return Client()
        gateway.httpx.AsyncClient = client_factory
        gateway.runtime_model_exists = persisted
        gateway.validate_route_dns = skip_dns
        gateway.runtime_models.clear()
        route = {
            "deployment_id": "deployment-1",
            "runtime_model_name": "openai/deepseek-v4-flash",
            "api_base": "http://host.docker.internal:39301/v1",
            "secret_version": "secret-1:v1",
            "api_key": "mock-key",
        }
        try:
            await gateway.ensure_runtime_model(route, force=True)
            self.assertIn("deployment-1", gateway.runtime_models)
            self.assertTrue(route.get("runtime_alias", "").startswith("ts-"))
            self.assertIs(False, client_kwargs.get("trust_env"))
        finally:
            gateway.httpx.AsyncClient = previous_client
            gateway.runtime_model_exists = previous_exists
            gateway.validate_route_dns = previous_dns
            gateway.runtime_models.clear()
            gateway.runtime_models.update(previous_models)

    async def test_effective_zero_price_is_allowed(self):
        class FakePool:
            async def fetchrow(self, *_):
                return {"id": "free", "currency": gateway.BUDGET_CURRENCY,
                        "cost_billing_basis": "TOKEN", "cost_billing_quantity": 1_000_000,
                        "input_cost_unit_price": Decimal("0"), "output_cost_unit_price": Decimal("0"),
                        "price_billing_basis": "TOKEN", "price_billing_quantity": 1_000_000,
                        "input_price_unit_price": Decimal("0"), "output_price_unit_price": Decimal("0"),
                        "cache_read_cost_unit_price": None, "cache_write_cost_unit_price": None,
                        "cache_read_mode": "NOT_APPLICABLE", "cache_write_mode": "NOT_APPLICABLE",
                        "component_schema_version": 2, "price_completeness_status": "UNSUPPORTED_CACHE",
                        "price_components": token_price_components("0", "0"), "internal_price_id": None}
        previous = gateway.pool
        gateway.pool = FakePool()
        try:
            price = await gateway.load_price("free", "platform", "provider", "model")
            self.assertEqual(Decimal("0"), price["input_price_unit_price"])
        finally:
            gateway.pool = previous

    async def test_current_public_reference_is_used_when_manual_price_is_absent(self):
        class FakePool:
            async def fetchrow(self, query, *_):
                if "v_effective_deployment_reference_price" not in query:
                    return None
                return {
                    "id": None, "price_layer": "PUBLIC_REFERENCE", "currency": gateway.BUDGET_CURRENCY,
                    "channel_deployment_id": "doubao-deployment", "cost_billing_basis": "TOKEN",
                    "cost_billing_quantity": 1_000_000, "input_cost_unit_price": Decimal("0.6"),
                    "output_cost_unit_price": Decimal("3.6"), "price_billing_basis": "TOKEN",
                    "price_billing_quantity": 1_000_000, "input_price_unit_price": Decimal("0.6"),
                    "output_price_unit_price": Decimal("3.6"), "cache_read_cost_unit_price": None,
                    "cache_write_cost_unit_price": None, "cache_read_mode": "UNKNOWN",
                    "cache_write_mode": "UNKNOWN", "component_schema_version": 2,
                    "price_completeness_status": "COMPLETE", "internal_price_id": None,
                    "source_ref": "bundle://doubao", "price_components": [
                        {"componentType": "INPUT_TOKEN", "unitPrice": "0.6", "billingBasis": "TOKEN", "billingQuantity": 1_000_000, "mode": "EXPLICIT"},
                        {"componentType": "OUTPUT_TOKEN", "unitPrice": "3.6", "billingBasis": "TOKEN", "billingQuantity": 1_000_000, "mode": "EXPLICIT"},
                    ],
                }
        previous = gateway.pool
        gateway.pool = FakePool()
        try:
            price = await gateway.load_price(None, "platform", "provider", "doubao-seed-2-0-lite-260215")
            self.assertEqual("PUBLIC_REFERENCE", price["price_layer"])
            self.assertEqual(1_000_000, price["price_components"][0]["unitQuantity"])
            cost, _, _, _ = gateway.calculate_amounts(price, {"prompt_tokens": 1_000_000, "completion_tokens": 0})
            self.assertEqual(Decimal("0.6"), cost)
        finally:
            gateway.pool = previous

    async def test_foreign_currency_price_uses_monthly_fx_for_budget_estimate(self):
        class FakePool:
            def __init__(self): self.calls = 0
            async def fetchrow(self, query, *args):
                self.calls += 1
                if "FROM channel_model_deployment" in query:
                    return {"id": "usd-price", "currency": "USD",
                            "cost_billing_basis": "TOKEN", "cost_billing_quantity": 1_000_000,
                            "input_cost_unit_price": Decimal("1"), "output_cost_unit_price": Decimal("2"),
                            "price_billing_basis": "TOKEN", "price_billing_quantity": 1_000_000,
                            "input_price_unit_price": Decimal("1"), "output_price_unit_price": Decimal("2"),
                            "cache_read_cost_unit_price": None, "cache_write_cost_unit_price": None,
                            "cache_read_mode": "NOT_APPLICABLE", "cache_write_mode": "NOT_APPLICABLE",
                            "component_schema_version": 2, "price_completeness_status": "UNSUPPORTED_CACHE",
                            "price_components": token_price_components("1", "2"), "internal_price_id": None}
                if "FROM fx_rate" in query:
                    return {"id": "fx-usd-cny", "rate": Decimal("7.2"), "source_date": date(2026, 7, 21),
                            "source_type": "AUTOMATIC_ECB", "source_ref": "https://www.ecb.europa.eu/"}
                return None
        previous = gateway.pool
        gateway.pool = FakePool()
        try:
            price = await gateway.load_price("usd-price", "platform", "provider", "model")
            self.assertEqual(Decimal("7.2"), price["budget_fx_rate"])
            self.assertEqual(gateway.BUDGET_CURRENCY, price["budget_currency"])
            estimate = gateway.estimate_price_reservation(price, 1_000_000)
            self.assertEqual(Decimal("14.4"), estimate)
        finally:
            gateway.pool = previous

    async def test_foreign_currency_price_fails_closed_when_monthly_fx_is_missing(self):
        class FakePool:
            async def fetchrow(self, query, *args):
                if "FROM channel_model_deployment" in query:
                    return {"id": "usd-price", "currency": "USD",
                            "cost_billing_basis": "TOKEN", "cost_billing_quantity": 1_000_000,
                            "input_cost_unit_price": Decimal("1"), "output_cost_unit_price": Decimal("2"),
                            "price_billing_basis": "TOKEN", "price_billing_quantity": 1_000_000,
                            "input_price_unit_price": Decimal("1"), "output_price_unit_price": Decimal("2"),
                            "cache_read_cost_unit_price": None, "cache_write_cost_unit_price": None,
                            "cache_read_mode": "NOT_APPLICABLE", "cache_write_mode": "NOT_APPLICABLE",
                            "component_schema_version": 2, "price_completeness_status": "UNSUPPORTED_CACHE",
                            "price_components": token_price_components("1", "2"), "internal_price_id": None}
                return None
        previous = gateway.pool
        gateway.pool = FakePool()
        try:
            with self.assertRaises(HTTPException) as error:
                await gateway.load_price("usd-price", "platform", "provider", "model")
            self.assertEqual("TOKENSEA_FX_RATE_MISSING", error.exception.detail["error_code"])
        finally:
            gateway.pool = previous

    async def test_secret_reference_is_bound_to_provider_instance(self):
        class FakePool:
            def __init__(self): self.call = None
            async def fetchrow(self, query, *args):
                self.call = (query, args)
                return {"id": "secret-1", "secret_cipher": "v2.AAECAwQFBgcICQoL.E229fqu2p3pl9j9jHkSQxQMzKLIZ6fpBpKT5jhZqmhFJIExNu6ey",
                        "updated_at": datetime(2026, 7, 10, tzinfo=timezone.utc)}
        previous_pool, previous_key = gateway.pool, gateway.CRYPTO_KEY
        fake = FakePool()
        gateway.pool = fake
        gateway.CRYPTO_KEY = "AAECAwQFBgcICQoLDA0ODxAREhMUFRYXGBkaGxwdHh8="
        try:
            value, version = await gateway.resolve_provider_secret({"id": "pi-1", "credential_ref": "secret:secret-1"})
            self.assertEqual("TokenSea跨语言密钥", value)
            self.assertIn("provider_instance_id=$2", fake.call[0])
            self.assertEqual(("secret-1", "pi-1"), fake.call[1])
            self.assertIn("secret-1", version)
            with self.assertRaises(HTTPException):
                await gateway.resolve_provider_secret({"id": "pi-1", "credential_ref": "env:PROVIDER_KEY"})
        finally:
            gateway.pool, gateway.CRYPTO_KEY = previous_pool, previous_key

    async def test_budget_reservation_has_token_and_settles_actual_delta(self):
        class FakePool:
            async def fetchval(self, *_): return Decimal("0")
        class FakeCache:
            def __init__(self): self.calls = []
            async def eval(self, script, count, *args):
                self.calls.append((script, count, args))
                return 0
        previous_pool, previous_cache = gateway.pool, gateway.cache
        gateway.pool, gateway.cache = FakePool(), FakeCache()
        key_ctx = {"id": "key-1", "tenant_id": "tenant-1", "project_id": None,
                   "budget_amount": Decimal("10"), "project_budget": None, "tenant_budget": None}
        route = {"price": {"cost_billing_basis": "TOKEN", "cost_billing_quantity": 1_000_000,
                           "input_cost_unit_price": Decimal("1000"), "output_cost_unit_price": Decimal("2000")}}
        try:
            reservation = await gateway.reserve_budget(key_ctx, [route], 1000, "request-1", "reservation-1")
            self.assertEqual("reservation-1", reservation["token"])
            self.assertEqual("ts:budget:reservation:request-1", reservation["state_key"])
            reserved = reservation["amount_micro"]
            await gateway.settle_budget(reservation, Decimal("3"))
            self.assertTrue(reservation["settled"])
            settle_call = gateway.cache.calls[-1]
            self.assertIs(settle_call[0], gateway.BUDGET_SETTLE_LUA)
            self.assertGreater(settle_call[2][-3], reserved)
        finally:
            gateway.pool, gateway.cache = previous_pool, previous_cache

    async def test_actual_over_reservation_is_recorded_as_overrun(self):
        class FakeCache:
            async def eval(self, script, count, *args):
                self.args = args
                return 1
        previous = gateway.cache
        gateway.cache = FakeCache()
        reservation = {"state_key": "state", "keys": ["budget"], "limits": [100],
                       "amount_micro": 10, "settled": False}
        try:
            result = await gateway.settle_budget(reservation, Decimal("0.000020"))
            self.assertEqual("OVERRUN", result)
            self.assertTrue(reservation["settled"])
            self.assertTrue(reservation["overrun"])
            self.assertIn("current+delta", gateway.BUDGET_SETTLE_LUA)
            self.assertIn("final_state='OVERRUN'", gateway.BUDGET_SETTLE_LUA)
        finally:
            gateway.cache = previous

    async def test_wal_survives_redis_failure_and_restart(self):
        class BrokenRedis:
            async def lpush(self, *_): raise RuntimeError("redis down")
        previous = (gateway.WAL_DIR, gateway.WAL_MAX_BYTES, gateway.cache, gateway.wal_pending)
        with tempfile.TemporaryDirectory() as directory:
            gateway.WAL_DIR = pathlib.Path(directory)
            gateway.WAL_MAX_BYTES = 1_048_576
            gateway.cache = BrokenRedis()
            gateway.initialize_wal()
            event_id = await gateway.enqueue_outbox("usage", {"id": "usage-1", "value": 1})
            self.assertIn(event_id, gateway.wal_pending)
            self.assertGreater(gateway.wal_path().stat().st_size, 0)
            gateway.wal_pending = {}
            gateway.initialize_wal()
            self.assertIn(event_id, gateway.wal_pending)
        gateway.WAL_DIR, gateway.WAL_MAX_BYTES, gateway.cache, gateway.wal_pending = previous

    async def test_finalize_keeps_success_and_reports_budget_overrun(self):
        class FakePool:
            def __init__(self): self.calls = []
            async def execute(self, query, *args): self.calls.append((query, args))
        class OverrunRedis:
            async def eval(self, *_): return 1
        previous_pool, previous_cache = gateway.pool, gateway.cache
        gateway.pool, gateway.cache = FakePool(), OverrunRedis()
        route = {"runtime_model_name": "openai/model", "provider_id": "pi", "price": {
            "id": "price", "currency": gateway.BUDGET_CURRENCY,
            "cost_billing_basis": "TOKEN", "cost_billing_quantity": 1_000_000,
            "input_cost_unit_price": "1000", "output_cost_unit_price": "1000",
            "price_billing_basis": "TOKEN", "price_billing_quantity": 1_000_000,
            "input_price_unit_price": "2000", "output_price_unit_price": "2000",
            "cache_read_cost_unit_price": None, "cache_write_cost_unit_price": None,
            "cache_read_mode": "NOT_APPLICABLE", "cache_write_mode": "NOT_APPLICABLE",
            "component_schema_version": 2, "price_completeness_status": "UNSUPPORTED_CACHE",
            "price_components": token_price_components("1000", "1000"), "internal_price_id": "internal"}}
        reservation = {"state_key": "state", "keys": ["budget"], "limits": [100],
                       "amount_micro": 1, "amount": Decimal("0.000001"), "settled": False}
        try:
            result = await gateway.finalize_request("req", {"tenant_id": "t", "id": "k"}, route, "alias",
                                                    {"prompt_tokens": 10, "completion_tokens": 5, "total_tokens": 15},
                                                    "SUCCESS", None, gateway.time.monotonic(), [], reservation)
            self.assertTrue(result["durable"])
            self.assertEqual("SUCCESS", result["usage_status"])
            self.assertEqual("OVERRUN", result["budget_status"])
            usage_args = next(args for query, args in gateway.pool.calls if "INSERT INTO usage_record" in query)
            self.assertEqual("SUCCESS", usage_args[15])
            self.assertEqual("TOKENSEA_BUDGET_OVERRUN", usage_args[16])
            self.assertGreater(usage_args[13], 0)
        finally:
            gateway.pool, gateway.cache = previous_pool, previous_cache

    async def test_public_dns_rotation_is_accepted_and_restricted_address_fails_closed(self):
        previous = gateway.resolve_dns_addresses
        previous_cache = dict(gateway.dns_validation_cache)
        route = {"provider_id": "provider-1", "api_base": "https://provider.example/v1",
                 "verified_host": "provider.example", "verified_port": 443,
                 "verified_addresses": "8.8.8.8"}
        calls = 0
        async def changed(_):
            nonlocal calls
            calls += 1
            return {"1.1.1.1"}
        async def restricted(_): return {"169.254.169.254"}
        try:
            gateway.dns_validation_cache.clear()
            gateway.resolve_dns_addresses = changed
            await gateway.validate_route_dns(route, force=True)
            await gateway.validate_route_dns(route)
            self.assertEqual(1, calls)
            gateway.dns_validation_cache.clear()
            gateway.resolve_dns_addresses = restricted
            with self.assertRaises(HTTPException) as restricted_error:
                await gateway.validate_route_dns(route, force=True)
            self.assertEqual("TOKENSEA_SSRF_TARGET_REJECTED", restricted_error.exception.detail["error_code"])
            route["verified_port"] = 8443
            with self.assertRaises(HTTPException) as port_error:
                await gateway.validate_route_dns(route, force=True)
            self.assertEqual("TOKENSEA_DNS_PORT_CHANGED", port_error.exception.detail["error_code"])
        finally:
            gateway.resolve_dns_addresses = previous
            gateway.dns_validation_cache.clear()
            gateway.dns_validation_cache.update(previous_cache)

    async def test_db_outbox_and_usage_v8_status_columns(self):
        class FakePool:
            def __init__(self): self.calls = []
            async def execute(self, query, *args): self.calls.append((query, args))
        previous = (gateway.pool, gateway.cache, gateway.WAL_DIR, gateway.wal_pending)
        with tempfile.TemporaryDirectory() as directory:
            gateway.pool, gateway.cache = FakePool(), None
            gateway.WAL_DIR = pathlib.Path(directory)
            gateway.initialize_wal()
            event_id = await gateway.enqueue_outbox("usage", {"request_id": "r1", "id": "u1"})
            self.assertTrue(any("accounting_outbox" in query for query, _ in gateway.pool.calls))
            self.assertIn(event_id, gateway.wal_pending)
            payload = {"id": "u1", "request_id": "r1", "model_alias": "m", "prompt_tokens": 1,
                "completion_tokens": 1, "total_tokens": 2, "cost_amount": "0", "sales_amount": "0",
                "currency": gateway.BUDGET_CURRENCY, "status": "SUCCESS", "latency_ms": 1,
                "fallback_chain": "[]", "price_version_id": "p", "budget_reserved_amount": "0",
                "budget_status": "SETTLED", "accounting_status": "COMMITTED",
                "price_components": {"INPUT_TOKEN": {"unitPrice": "1"}},
                "cost_components": {"OUTPUT_TOKEN": {"amount": "2"}}}
            await gateway.persist_usage(payload)
            usage_query, usage_args = next((query, args) for query, args in gateway.pool.calls if "INSERT INTO usage_record" in query)
            self.assertIn("budget_status,accounting_status", usage_query)
            self.assertEqual(("SETTLED", "COMMITTED"), usage_args[-2:])
            snapshot_query, snapshot_args = next((query, args) for query, args in gateway.pool.calls if "INSERT INTO usage_cost_snapshot" in query)
            self.assertIn("$35,$36::jsonb", snapshot_query)
            self.assertIn("$42", snapshot_query)
            self.assertEqual(42, len(snapshot_args))
            self.assertEqual(2, snapshot_args[33])
            self.assertEqual("UNKNOWN", snapshot_args[34])
            self.assertEqual({}, json.loads(snapshot_args[35]))
            self.assertEqual("COMPLETE", snapshot_args[-1])
            self.assertEqual("INPUT_TOKEN", json.loads(snapshot_args[21])[0]["componentType"])
            self.assertEqual("OUTPUT_TOKEN", json.loads(snapshot_args[22])[0]["componentType"])
        gateway.pool, gateway.cache, gateway.WAL_DIR, gateway.wal_pending = previous

    async def test_stream_intent_remains_pending_and_recovers_incomplete_usage(self):
        class FakePool:
            def __init__(self): self.calls = []
            async def execute(self, query, *args): self.calls.append((query, args))
            async def fetchval(self, *_): return None
        previous = (gateway.pool, gateway.cache, gateway.WAL_DIR, gateway.wal_pending)
        with tempfile.TemporaryDirectory() as directory:
            gateway.pool, gateway.cache = FakePool(), None
            gateway.WAL_DIR = pathlib.Path(directory)
            gateway.initialize_wal()
            budget = {"keys": [], "settled": True, "amount": Decimal("0")}
            route = {"runtime_model_name": "openai/m", "provider_id": "pi",
                     "price": {"id": "price", "currency": gateway.BUDGET_CURRENCY}}
            intent_id = await gateway.create_request_intent("stream-r", {"id": "key", "tenant_id": "tenant"},
                                                            route, "alias", budget)
            self.assertIn(intent_id, gateway.wal_pending)
            event = gateway.wal_pending[intent_id]
            self.assertEqual("request_intent", event["kind"])
            await gateway.process_outbox(event)
            usage_calls = [(query, args) for query, args in gateway.pool.calls if "INSERT INTO usage_record" in query]
            self.assertTrue(usage_calls)
            self.assertEqual("TOKENSEA_ACCOUNTING_RECOVERED_INCOMPLETE", usage_calls[-1][1][16])
            self.assertEqual("COMMITTED", usage_calls[-1][1][-1])
        gateway.pool, gateway.cache, gateway.WAL_DIR, gateway.wal_pending = previous

    async def test_request_intent_requires_db_and_fsync_wal(self):
        class BrokenPool:
            async def execute(self, *_): raise RuntimeError("db down")
        previous = (gateway.pool, gateway.cache, gateway.WAL_DIR, gateway.wal_pending)
        with tempfile.TemporaryDirectory() as directory:
            gateway.pool, gateway.cache = BrokenPool(), None
            gateway.WAL_DIR = pathlib.Path(directory)
            gateway.wal_pending = {}
            gateway.initialize_wal()
            budget = {"keys": [], "settled": True, "amount": Decimal("0")}
            route = {"runtime_model_name": "openai/m", "provider_id": "pi",
                     "price": {"id": "price", "currency": gateway.BUDGET_CURRENCY}}
            with self.assertRaisesRegex(RuntimeError, "PostgreSQL and WAL"):
                await gateway.create_request_intent("both-r", {"id": "key", "tenant_id": "tenant"},
                                                    route, "alias", budget)
            self.assertTrue(any(event["kind"] == "request_intent"
                                for event in gateway.wal_pending.values()))
        gateway.pool, gateway.cache, gateway.WAL_DIR, gateway.wal_pending = previous

    async def test_startup_activates_crashed_request_intent_immediately(self):
        class FakePool:
            def __init__(self): self.calls = []
            async def execute(self, query, *args): self.calls.append((query, args))
        previous = (gateway.pool, gateway.WAL_DIR, gateway.wal_pending)
        with tempfile.TemporaryDirectory() as directory:
            fake = FakePool()
            gateway.pool = fake
            gateway.WAL_DIR = pathlib.Path(directory)
            gateway.wal_pending = {}
            gateway.initialize_wal()
            future = time.time() + 86400
            event_id = gateway.wal_put("request_intent", {"request_id": "crashed-r"},
                                       "intent-crashed-r", future)
            await gateway.activate_recovered_request_intents()
            self.assertLessEqual(gateway.wal_pending[event_id]["available_at"], time.time())
            self.assertTrue(any("accounting_outbox" in query for query, _ in fake.calls))
            self.assertTrue(any("event_type='request_intent'" in query for query, _ in fake.calls))
        gateway.pool, gateway.WAL_DIR, gateway.wal_pending = previous

    def test_removed_probe_never_puts_then_acks_before_stream(self):
        self.assertFalse(hasattr(gateway, "wal_durability_probe"))
        source = MODULE_PATH.read_text(encoding="utf-8")
        self.assertNotIn('"durability_probe"', source)

    async def test_wal_dead_letter_does_not_block_next_event(self):
        previous = (gateway.WAL_DIR, gateway.wal_pending, gateway.pool, gateway.process_outbox,
                    gateway.OUTBOX_MAX_ATTEMPTS)
        processed = []
        with tempfile.TemporaryDirectory() as directory:
            gateway.WAL_DIR = pathlib.Path(directory)
            gateway.pool = None
            gateway.OUTBOX_MAX_ATTEMPTS = 2
            gateway.initialize_wal()
            first = gateway.wal_put("bad", {"id": "bad"}, "bad-id", time.time() - 1)
            bad = dict(gateway.wal_pending[first]); bad["attempts"] = 1
            gateway.wal_replace(first, bad)
            second = gateway.wal_put("good", {"id": "good"}, "good-id", time.time() - 1)
            async def process(envelope):
                if envelope["kind"] == "bad": raise RuntimeError("permanent")
                processed.append(envelope["kind"])
            gateway.process_outbox = process
            await gateway.process_wal_outbox_once()
            self.assertNotIn(first, gateway.wal_pending)
            self.assertTrue((gateway.WAL_DIR / gateway.WAL_DEAD_FILE_NAME).exists())
            await gateway.process_wal_outbox_once()
            self.assertEqual(["good"], processed)
            self.assertNotIn(second, gateway.wal_pending)
        (gateway.WAL_DIR, gateway.wal_pending, gateway.pool, gateway.process_outbox,
         gateway.OUTBOX_MAX_ATTEMPTS) = previous

    async def test_release_dual_failure_is_not_silently_accepted(self):
        class BrokenRedis:
            async def eval(self, *_): raise RuntimeError("redis down")
        class BrokenDb:
            async def execute(self, *_): raise RuntimeError("db down")
        previous = (gateway.pool, gateway.cache, gateway.WAL_DIR, gateway.wal_pending)
        with tempfile.TemporaryDirectory() as directory:
            invalid = pathlib.Path(directory) / "not-a-directory"
            invalid.write_text("blocked", encoding="utf-8")
            gateway.pool, gateway.cache, gateway.WAL_DIR = BrokenDb(), BrokenRedis(), invalid
            gateway.wal_pending = {}
            result = await gateway.release_budget_safely({"state_key": "s", "keys": ["k"],
                                                          "settled": False, "limits": [1]})
            self.assertFalse(result)
        gateway.pool, gateway.cache, gateway.WAL_DIR, gateway.wal_pending = previous

    async def test_execution_surfaces_release_double_failure(self):
        previous = (gateway.require_runtime_settings, gateway.validate_key, gateway.select_routes,
                    gateway.reserve_rate_limits, gateway.reserve_budget, gateway.execute_non_stream,
                    gateway.release_budget_safely)
        gateway.require_runtime_settings = lambda: None
        async def validate(*_): return {"id": "key", "tenant_id": "tenant"}
        async def routes(*_): return [{"price": {"id": "p", "currency": gateway.BUDGET_CURRENCY}}]
        async def no_limit(*_): return None
        async def reserve(*_): return {"keys": ["budget"], "settled": False}
        async def execute(*_): raise gateway.gateway_error(502, "UPSTREAM_FAILED", "upstream failed")
        async def cannot_release(*_): return False
        gateway.validate_key, gateway.select_routes = validate, routes
        gateway.reserve_rate_limits, gateway.reserve_budget = no_limit, reserve
        gateway.execute_non_stream, gateway.release_budget_safely = execute, cannot_release
        request = types.SimpleNamespace(headers={"authorization": "Bearer token"},
                                        client=types.SimpleNamespace(host="127.0.0.1"))
        async def body(): return {"model": "alias", "messages": [{"role": "user", "content": "x"}]}
        request.json = body
        try:
            with self.assertRaises(HTTPException) as error:
                await gateway.proxy_openai_compatible(request, "/v1/chat/completions")
            self.assertEqual("TOKENSEA_ACCOUNTING_RELEASE_FAILED", error.exception.detail["error_code"])
        finally:
            (gateway.require_runtime_settings, gateway.validate_key, gateway.select_routes,
             gateway.reserve_rate_limits, gateway.reserve_budget, gateway.execute_non_stream,
             gateway.release_budget_safely) = previous


if __name__ == "__main__":
    unittest.main()
