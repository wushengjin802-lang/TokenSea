import importlib.util
import pathlib
import sys
import tempfile
import time
import types
import unittest
from decimal import Decimal
from datetime import datetime, timezone

from fastapi import HTTPException

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


class RuntimeRulesTest(unittest.TestCase):
    def test_scope_requires_non_empty_json_array(self):
        for invalid in (None, "", "[]", "{}", "not-json", '["", "chat"]'):
            with self.subTest(invalid=invalid), self.assertRaises(HTTPException):
                gateway.parse_scope(invalid)

    def test_scope_is_deduplicated(self):
        self.assertEqual(["chat-standard"], gateway.parse_scope('["chat-standard", "chat-standard"]'))

    def test_runtime_model_uses_provider_adapter(self):
        self.assertEqual("anthropic/claude-test", gateway.runtime_model_name({"api_style": "anthropic"}, "claude-test"))
        self.assertEqual("openai/qwen-test", gateway.runtime_model_name({"api_style": "openai_compatible"}, "qwen-test"))
        self.assertEqual("openrouter/model", gateway.runtime_model_name({"api_style": "openai_compatible"}, "openrouter/model"))

    def test_provider_requires_real_connection_result(self):
        configured = {"api_base": "https://provider.example/v1", "status": "启用", "health_status": "健康", "last_connection_test_status": "成功", "last_connection_test_at": datetime.now(timezone.utc), "last_connection_test_host": "provider.example", "last_connection_test_addresses": "8.8.8.8", "key_status": "已托管"}
        self.assertTrue(gateway.provider_is_routable(configured))
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
        gateway.validate_visibility('["t1"]', {"tenant_id": "t1", "tenant_type": "INTERNAL"})
        with self.assertRaises(HTTPException):
            gateway.validate_visibility('["t2"]', {"tenant_id": "t1", "tenant_type": "INTERNAL"})
        cost, sales = gateway.calculate_amounts({
            "input_cost_per_1k": "1", "output_cost_per_1k": "2",
            "input_price_per_1k": "3", "output_price_per_1k": "4"
        }, 1000, 500)
        self.assertEqual(gateway.Decimal("2"), cost)
        self.assertEqual(gateway.Decimal("5"), sales)
        zero_cost, zero_sales = gateway.calculate_amounts({
            "input_cost_per_1k": "0", "output_cost_per_1k": "0",
            "input_price_per_1k": "0", "output_price_per_1k": "0"
        }, 1000, 500)
        self.assertEqual(Decimal("0"), zero_cost)
        self.assertEqual(Decimal("0"), zero_sales)

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
    async def test_effective_zero_price_is_allowed(self):
        class FakePool:
            async def fetchrow(self, *_):
                return {"id": "free", "currency": gateway.BUDGET_CURRENCY,
                        "input_cost_per_1k": Decimal("0"), "output_cost_per_1k": Decimal("0"),
                        "input_price_per_1k": Decimal("0"), "output_price_per_1k": Decimal("0")}
        previous = gateway.pool
        gateway.pool = FakePool()
        try:
            price = await gateway.load_price("free", "platform", "provider", "model")
            self.assertEqual(Decimal("0"), price["input_price_per_1k"])
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
        route = {"price": {"input_cost_per_1k": Decimal("1"), "output_cost_per_1k": Decimal("2")}}
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
            "id": "price", "currency": gateway.BUDGET_CURRENCY, "input_cost_per_1k": "1",
            "output_cost_per_1k": "1", "input_price_per_1k": "2", "output_price_per_1k": "2"}}
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

    async def test_dns_snapshot_change_and_restricted_address_fail_closed(self):
        previous = gateway.resolve_dns_addresses
        route = {"api_base": "https://provider.example/v1", "verified_host": "provider.example",
                 "verified_addresses": "8.8.8.8"}
        async def same(_): return {"8.8.8.8"}
        async def changed(_): return {"1.1.1.1"}
        try:
            gateway.resolve_dns_addresses = same
            await gateway.validate_route_dns(route, force=True)
            gateway.resolve_dns_addresses = changed
            with self.assertRaises(HTTPException) as changed_error:
                await gateway.validate_route_dns(route, force=True)
            self.assertEqual("TOKENSEA_DNS_SNAPSHOT_CHANGED", changed_error.exception.detail["error_code"])
            with self.assertRaises(HTTPException):
                gateway.validate_public_addresses({"169.254.169.254"})
        finally:
            gateway.resolve_dns_addresses = previous

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
                "budget_status": "SETTLED", "accounting_status": "COMMITTED"}
            await gateway.persist_usage(payload)
            usage_query, usage_args = next((query, args) for query, args in gateway.pool.calls if "INSERT INTO usage_record" in query)
            self.assertIn("budget_status,accounting_status", usage_query)
            self.assertEqual(("SETTLED", "COMMITTED"), usage_args[-2:])
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
