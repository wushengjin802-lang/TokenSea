import asyncio
import ipaddress
import socket
import sys
from pathlib import Path

import pytest


sys.path.insert(0, str(Path(__file__).resolve().parents[1]))
from app.proxy import (EgressPolicy, EgressProxy, ProxyError, parse_absolute_http_target,
                       parse_allowed_hosts, parse_authority)


class FakeReader:
    def __init__(self, result=None, error=None):
        self.result = result
        self.error = error

    async def readuntil(self, separator):
        if self.error:
            raise self.error
        return self.result


class FakeWriter:
    def __init__(self):
        self.data = bytearray()
        self.closed = False

    def write(self, data):
        self.data.extend(data)

    async def drain(self):
        return None

    def close(self):
        self.closed = True

    async def wait_closed(self):
        return None

    def is_closing(self):
        return self.closed


@pytest.mark.parametrize("address", [
    "127.0.0.1", "10.0.0.1", "172.16.0.1", "192.168.1.1",
    "169.254.169.254", "100.64.0.1", "0.0.0.0", "224.0.0.1",
    "192.0.2.1", "::1", "fe80::1", "fc00::1", "ff02::1",
])
def test_rejects_non_global_and_metadata_addresses(address):
    with pytest.raises(ProxyError) as error:
        EgressPolicy.validate_ip(address)
    assert error.value.code == "non_global_address"


def test_accepts_global_addresses_only():
    assert EgressPolicy.validate_ip("8.8.8.8") == ipaddress.ip_address("8.8.8.8")
    assert EgressPolicy.validate_ip("2606:4700:4700::1111").is_global


def test_allowlist_is_exact_and_empty_is_fail_closed():
    policy = EgressPolicy(parse_allowed_hosts("API.Example.COM.,other.example"), frozenset({443}))
    assert policy.validate_host_port("api.example.com", 443) == "api.example.com"
    with pytest.raises(ProxyError):
        policy.validate_host_port("sub.api.example.com", 443)
    with pytest.raises(ProxyError):
        EgressPolicy(frozenset(), frozenset({443})).validate_host_port("api.example.com", 443)


def test_wildcard_allowlist_is_rejected():
    with pytest.raises(RuntimeError):
        parse_allowed_hosts("*.example.com")


def test_dynamic_allowlist_is_merged_without_changing_baseline():
    policy = EgressPolicy(frozenset({"api.example.com"}), frozenset({443}))
    policy.replace_dynamic_hosts(["prices.example.com"])
    assert policy.validate_host_port("api.example.com", 443) == "api.example.com"
    assert policy.validate_host_port("prices.example.com", 443) == "prices.example.com"
    assert policy.allowed_hosts == frozenset({"api.example.com"})
    policy.replace_dynamic_hosts([])
    with pytest.raises(ProxyError):
        policy.validate_host_port("prices.example.com", 443)


def test_connect_authority_parsing():
    assert parse_authority("api.example.com:443") == ("api.example.com", 443)
    assert parse_authority("[2606:4700:4700::1111]:443") == ("2606:4700:4700::1111", 443)
    for invalid in ("api.example.com", "user@api.example.com:443", "api.example.com:0", "api.example.com:notaport"):
        with pytest.raises(ProxyError):
            parse_authority(invalid)


def test_absolute_http_form_parsing():
    assert parse_absolute_http_target("http://api.example.com/v1?q=1") == ("api.example.com", 80, "/v1?q=1")
    with pytest.raises(ProxyError):
        parse_absolute_http_target("https://api.example.com/v1")


def test_origin_form_preserves_host_and_strips_proxy_credentials():
    request = EgressProxy._origin_request(
        "GET", "/v1/models", "HTTP/1.1",
        [("Host", "api.example.com"), ("Authorization", "Bearer upstream-secret"),
         ("Proxy-Authorization", "Basic proxy-secret"), ("Proxy-Connection", "keep-alive")],
    ).decode("iso-8859-1")
    assert "Host: api.example.com\r\n" in request
    assert "Authorization: Bearer upstream-secret\r\n" in request
    assert "Proxy-Authorization" not in request
    assert "Proxy-Connection" not in request
    assert request.endswith("Connection: close\r\n\r\n")


def test_uncommitted_timeout_returns_http_408():
    proxy = EgressProxy(EgressPolicy(frozenset({"api.example.com"}), frozenset({443})))
    client = FakeWriter()
    asyncio.run(proxy.handle_client(FakeReader(error=asyncio.TimeoutError()), client))
    assert bytes(client.data).startswith(b"HTTP/1.1 408 Request Timeout\r\n")


def test_committed_connect_timeout_closes_without_plaintext_408():
    proxy = EgressProxy(EgressPolicy(frozenset({"api.example.com"}), frozenset({443})))
    upstream = FakeWriter()

    async def connected(host, port):
        return host, "8.8.8.8", (FakeReader(), upstream)

    async def tunnel_timeout(*args, **kwargs):
        raise asyncio.TimeoutError()

    proxy.resolve_and_connect = connected
    proxy._relay_bidirectional = tunnel_timeout
    client = FakeWriter()
    request = b"CONNECT api.example.com:443 HTTP/1.1\r\nHost: api.example.com:443\r\n\r\n"
    asyncio.run(proxy.handle_client(FakeReader(result=request), client))
    assert bytes(client.data) == b"HTTP/1.1 200 Connection Established\r\n\r\n"
    assert b"408 Request Timeout" not in client.data
    assert client.closed is True
    assert upstream.closed is True


def test_dns_resolution_is_pinned_to_numeric_ip():
    calls = []

    async def resolver(host, port):
        calls.append(("resolve", host, port))
        return [(socket.AF_INET, socket.SOCK_STREAM, socket.IPPROTO_TCP, "", ("8.8.8.8", port))]

    async def connector(address, port, family):
        calls.append(("connect", address, port, family))
        return object(), object()

    proxy = EgressProxy(EgressPolicy(frozenset({"api.example.com"}), frozenset({443})),
                        resolver=resolver, connector=connector)
    normalized, pinned, _ = asyncio.run(proxy.resolve_and_connect("api.example.com", 443))
    assert normalized == "api.example.com"
    assert pinned == "8.8.8.8"
    assert calls == [("resolve", "api.example.com", 443), ("connect", "8.8.8.8", 443, socket.AF_INET)]


def test_mixed_dns_answer_rejects_before_connect():
    connected = False

    async def resolver(host, port):
        return [
            (socket.AF_INET, socket.SOCK_STREAM, socket.IPPROTO_TCP, "", ("8.8.8.8", port)),
            (socket.AF_INET, socket.SOCK_STREAM, socket.IPPROTO_TCP, "", ("169.254.169.254", port)),
        ]

    async def connector(address, port, family):
        nonlocal connected
        connected = True
        return object(), object()

    proxy = EgressProxy(EgressPolicy(frozenset({"api.example.com"}), frozenset({443})),
                        resolver=resolver, connector=connector)
    with pytest.raises(ProxyError) as error:
        asyncio.run(proxy.resolve_and_connect("api.example.com", 443))
    assert error.value.code == "non_global_address"
    assert connected is False
