import asyncio
import ipaddress
import logging
import os
import socket
from dataclasses import dataclass
from typing import Awaitable, Callable, Iterable, Optional, Sequence
from urllib.parse import urlsplit, urlunsplit


LOGGER = logging.getLogger("tokensea-egress-proxy")
logging.basicConfig(level=os.getenv("TOKENSEA_EGRESS_LOG_LEVEL", "INFO"),
                    format="%(asctime)s %(levelname)s %(message)s")


class ProxyError(Exception):
    def __init__(self, status: int, reason: str, code: str):
        super().__init__(code)
        self.status = status
        self.reason = reason
        self.code = code


def normalize_hostname(value: str) -> str:
    host = value.strip().rstrip(".")
    if not host or any(character.isspace() for character in host):
        raise ProxyError(400, "Bad Request", "invalid_host")
    try:
        return host.encode("idna").decode("ascii").lower()
    except UnicodeError as exc:
        raise ProxyError(400, "Bad Request", "invalid_host") from exc


def parse_allowed_hosts(raw: str) -> frozenset[str]:
    hosts = set()
    for item in raw.split(","):
        if not item.strip():
            continue
        if "*" in item:
            raise RuntimeError("TOKENSEA_ALLOWED_EGRESS_HOSTS does not support wildcards")
        hosts.add(normalize_hostname(item))
    return frozenset(hosts)


def parse_allowed_ports(raw: str) -> frozenset[int]:
    ports = set()
    for item in raw.split(","):
        if not item.strip():
            continue
        try:
            port = int(item)
        except ValueError as exc:
            raise RuntimeError("TOKENSEA_EGRESS_ALLOWED_PORTS must be comma-separated integers") from exc
        if port < 1 or port > 65535:
            raise RuntimeError("TOKENSEA_EGRESS_ALLOWED_PORTS contains an invalid port")
        ports.add(port)
    if not ports:
        raise RuntimeError("TOKENSEA_EGRESS_ALLOWED_PORTS must not be empty")
    return frozenset(ports)


def parse_authority(authority: str) -> tuple[str, int]:
    value = authority.strip()
    if not value or "@" in value or "/" in value or "#" in value or "?" in value:
        raise ProxyError(400, "Bad Request", "invalid_connect_authority")
    if value.startswith("["):
        close = value.find("]")
        if close < 0 or close + 1 >= len(value) or value[close + 1] != ":":
            raise ProxyError(400, "Bad Request", "invalid_connect_authority")
        host, port_text = value[1:close], value[close + 2:]
    else:
        if value.count(":") != 1:
            raise ProxyError(400, "Bad Request", "invalid_connect_authority")
        host, port_text = value.rsplit(":", 1)
    try:
        port = int(port_text)
    except ValueError as exc:
        raise ProxyError(400, "Bad Request", "invalid_connect_port") from exc
    if port < 1 or port > 65535:
        raise ProxyError(400, "Bad Request", "invalid_connect_port")
    return normalize_hostname(host), port


def parse_absolute_http_target(target: str) -> tuple[str, int, str]:
    try:
        parsed = urlsplit(target)
        port = parsed.port
    except ValueError as exc:
        raise ProxyError(400, "Bad Request", "invalid_absolute_uri") from exc
    if parsed.scheme.lower() != "http" or not parsed.hostname or parsed.username is not None or parsed.password is not None:
        raise ProxyError(400, "Bad Request", "absolute_http_uri_required")
    if parsed.fragment:
        raise ProxyError(400, "Bad Request", "fragment_not_allowed")
    host = normalize_hostname(parsed.hostname)
    origin_target = urlunsplit(("", "", parsed.path or "/", parsed.query, ""))
    return host, port or 80, origin_target


def parse_host_header(value: str, default_port: int) -> tuple[str, int]:
    text = value.strip()
    if text.startswith("["):
        close = text.find("]")
        if close < 0:
            raise ProxyError(400, "Bad Request", "invalid_host_header")
        host = text[1:close]
        suffix = text[close + 1:]
        if suffix and not suffix.startswith(":"):
            raise ProxyError(400, "Bad Request", "invalid_host_header")
        port_text = suffix[1:] if suffix else ""
    elif text.count(":") == 1:
        host, port_text = text.rsplit(":", 1)
    elif ":" in text:
        host, port_text = text, ""
    else:
        host, port_text = text, ""
    try:
        port = int(port_text) if port_text else default_port
    except ValueError as exc:
        raise ProxyError(400, "Bad Request", "invalid_host_header") from exc
    return normalize_hostname(host), port


@dataclass(frozen=True)
class EgressPolicy:
    allowed_hosts: frozenset[str]
    allowed_ports: frozenset[int]

    def validate_host_port(self, host: str, port: int) -> str:
        normalized = normalize_hostname(host)
        if not self.allowed_hosts or normalized not in self.allowed_hosts:
            raise ProxyError(403, "Forbidden", "host_not_allowlisted")
        if port not in self.allowed_ports:
            raise ProxyError(403, "Forbidden", "port_not_allowlisted")
        return normalized

    @staticmethod
    def validate_ip(value: str) -> ipaddress.IPv4Address | ipaddress.IPv6Address:
        try:
            address = ipaddress.ip_address(value)
        except ValueError as exc:
            raise ProxyError(403, "Forbidden", "invalid_resolved_address") from exc
        if isinstance(address, ipaddress.IPv6Address) and address.ipv4_mapped:
            address = address.ipv4_mapped
        # is_global rejects loopback, private, link-local, metadata, CGNAT/shared,
        # reserved, unspecified, documentation and multicast ranges for both families.
        if (address.is_loopback or address.is_private or address.is_link_local or
                address.is_multicast or address.is_reserved or address.is_unspecified or
                not address.is_global):
            raise ProxyError(403, "Forbidden", "non_global_address")
        return address


Resolver = Callable[[str, int], Awaitable[Sequence[tuple]]]
Connector = Callable[[str, int, int], Awaitable[tuple[asyncio.StreamReader, asyncio.StreamWriter]]]


async def system_resolver(host: str, port: int) -> Sequence[tuple]:
    loop = asyncio.get_running_loop()
    return await loop.getaddrinfo(host, port, type=socket.SOCK_STREAM, proto=socket.IPPROTO_TCP)


async def numeric_connector(address: str, port: int, family: int):
    return await asyncio.open_connection(address, port, family=family, flags=socket.AI_NUMERICHOST)


class EgressProxy:
    def __init__(self, policy: EgressPolicy, *, resolver: Resolver = system_resolver,
                 connector: Connector = numeric_connector, connect_timeout: float = 10,
                 idle_timeout: float = 300, header_timeout: float = 10,
                 max_header_bytes: int = 65536, max_headers: int = 100,
                 max_request_bytes: int = 268435456, max_response_bytes: int = 268435456):
        self.policy = policy
        self.resolver = resolver
        self.connector = connector
        self.connect_timeout = connect_timeout
        self.idle_timeout = idle_timeout
        self.header_timeout = header_timeout
        self.max_header_bytes = max_header_bytes
        self.max_headers = max_headers
        self.max_request_bytes = max_request_bytes
        self.max_response_bytes = max_response_bytes

    async def resolve_and_connect(self, host: str, port: int):
        normalized = self.policy.validate_host_port(host, port)
        try:
            answers = await asyncio.wait_for(self.resolver(normalized, port), self.connect_timeout)
        except (asyncio.TimeoutError, socket.gaierror) as exc:
            raise ProxyError(502, "Bad Gateway", "dns_resolution_failed") from exc
        candidates: list[ipaddress.IPv4Address | ipaddress.IPv6Address] = []
        for answer in answers:
            if len(answer) < 5 or not answer[4]:
                continue
            address = self.policy.validate_ip(answer[4][0])
            if address not in candidates:
                candidates.append(address)
        if not candidates:
            raise ProxyError(403, "Forbidden", "no_safe_address")
        last_error: Optional[BaseException] = None
        for address in candidates:
            family = socket.AF_INET6 if address.version == 6 else socket.AF_INET
            try:
                streams = await asyncio.wait_for(self.connector(str(address), port, family), self.connect_timeout)
                return normalized, str(address), streams
            except (asyncio.TimeoutError, ConnectionError, OSError) as exc:
                last_error = exc
        raise ProxyError(502, "Bad Gateway", "upstream_connect_failed") from last_error

    async def handle_client(self, reader: asyncio.StreamReader, writer: asyncio.StreamWriter):
        method = "UNKNOWN"
        target_host = "invalid"
        target_port = 0
        outcome = "rejected"
        proxy_response_committed = False
        upstream_writer: Optional[asyncio.StreamWriter] = None
        try:
            raw = await asyncio.wait_for(reader.readuntil(b"\r\n\r\n"), self.header_timeout)
            if len(raw) > self.max_header_bytes:
                raise ProxyError(431, "Request Header Fields Too Large", "headers_too_large")
            method, target, version, headers = self._parse_request(raw)
            if method == "CONNECT":
                target_host, target_port = parse_authority(target)
                normalized, pinned_ip, (upstream_reader, upstream_writer) = await self.resolve_and_connect(target_host, target_port)
                writer.write(b"HTTP/1.1 200 Connection Established\r\n\r\n")
                await writer.drain()
                proxy_response_committed = True
                outcome = "connected"
                await self._relay_bidirectional(reader, writer, upstream_reader, upstream_writer,
                                                finish_on_either=True)
            else:
                target_host, target_port, origin_target = parse_absolute_http_target(target)
                host_values = [value for name, value in headers if name.lower() == "host"]
                if len(host_values) != 1 or parse_host_header(host_values[0], 80) != (target_host, target_port):
                    raise ProxyError(400, "Bad Request", "host_target_mismatch")
                normalized, pinned_ip, (upstream_reader, upstream_writer) = await self.resolve_and_connect(target_host, target_port)
                upstream_writer.write(self._origin_request(method, origin_target, version, headers))
                await upstream_writer.drain()
                proxy_response_committed = True
                outcome = "connected"
                await self._relay_bidirectional(reader, writer, upstream_reader, upstream_writer,
                                                finish_on_either=False)
            outcome = "completed"
        except asyncio.IncompleteReadError:
            outcome = "incomplete_request"
        except asyncio.LimitOverrunError:
            await self._send_error(writer, ProxyError(431, "Request Header Fields Too Large", "headers_too_large"))
            outcome = "headers_too_large"
        except asyncio.TimeoutError:
            if not proxy_response_committed:
                await self._send_error(writer, ProxyError(408, "Request Timeout", "request_timeout"))
            outcome = "timeout"
        except ProxyError as exc:
            if not proxy_response_committed:
                await self._send_error(writer, exc)
            outcome = exc.code
        except Exception:
            LOGGER.exception("proxy_internal_error method=%s host=%s port=%s", method, target_host, target_port)
            if not proxy_response_committed:
                await self._send_error(writer, ProxyError(502, "Bad Gateway", "proxy_internal_error"))
            outcome = "proxy_internal_error"
        finally:
            if upstream_writer:
                upstream_writer.close()
                try:
                    await upstream_writer.wait_closed()
                except (ConnectionError, OSError):
                    pass
            writer.close()
            try:
                await writer.wait_closed()
            except (ConnectionError, OSError):
                pass
            # Never log paths, query strings, headers or credentials.
            if method != "UNKNOWN" or outcome != "incomplete_request":
                LOGGER.info("egress method=%s host=%s port=%s outcome=%s", method, target_host, target_port, outcome)

    def _parse_request(self, raw: bytes):
        try:
            lines = raw[:-4].decode("iso-8859-1").split("\r\n")
            method, target, version = lines[0].split(" ")
        except (UnicodeDecodeError, ValueError, IndexError) as exc:
            raise ProxyError(400, "Bad Request", "invalid_request_line") from exc
        if not method.isalpha() or method.upper() != method or version not in ("HTTP/1.0", "HTTP/1.1"):
            raise ProxyError(400, "Bad Request", "invalid_request_line")
        if len(lines) - 1 > self.max_headers:
            raise ProxyError(431, "Request Header Fields Too Large", "too_many_headers")
        headers = []
        for line in lines[1:]:
            if not line or line[0].isspace() or ":" not in line:
                raise ProxyError(400, "Bad Request", "invalid_header")
            name, value = line.split(":", 1)
            if not name or any(character.isspace() for character in name):
                raise ProxyError(400, "Bad Request", "invalid_header")
            headers.append((name, value.lstrip(" \t")))
        return method, target, version, headers

    @staticmethod
    def _origin_request(method: str, target: str, version: str, headers: Iterable[tuple[str, str]]) -> bytes:
        lines = [f"{method} {target} {version}"]
        for name, value in headers:
            if name.lower() in ("proxy-authorization", "proxy-connection", "connection"):
                continue
            lines.append(f"{name}: {value}")
        lines.append("Connection: close")
        return ("\r\n".join(lines) + "\r\n\r\n").encode("iso-8859-1")

    async def _relay_bidirectional(self, client_reader, client_writer, upstream_reader, upstream_writer,
                                   *, finish_on_either: bool):
        async def relay(source, destination, limit: int):
            transferred = 0
            while True:
                chunk = await asyncio.wait_for(source.read(65536), self.idle_timeout)
                if not chunk:
                    try:
                        destination.write_eof()
                    except (AttributeError, OSError):
                        pass
                    return
                transferred += len(chunk)
                if transferred > limit:
                    raise ProxyError(413, "Content Too Large", "transfer_limit_exceeded")
                destination.write(chunk)
                await destination.drain()

        request_task = asyncio.create_task(relay(client_reader, upstream_writer, self.max_request_bytes))
        response_task = asyncio.create_task(relay(upstream_reader, client_writer, self.max_response_bytes))
        tasks = [request_task, response_task]
        done, pending = await asyncio.wait(tasks, return_when=asyncio.FIRST_COMPLETED)
        first_error = None
        for task in done:
            try:
                task.result()
            except BaseException as exc:
                first_error = exc
        # A CONNECT tunnel closes when either half closes. For plain HTTP, a fully
        # uploaded request must still be allowed to receive its response, while an
        # upstream-complete response closes the client upload half immediately.
        if not first_error and not finish_on_either and request_task in done and response_task in pending:
            done2, pending = await asyncio.wait(pending)
            for task in done2:
                try:
                    task.result()
                except BaseException as exc:
                    first_error = exc
        for task in pending:
            task.cancel()
        await asyncio.gather(*pending, return_exceptions=True)
        if first_error:
            raise first_error

    @staticmethod
    async def _send_error(writer: asyncio.StreamWriter, error: ProxyError):
        if writer.is_closing():
            return
        body = f"{error.status} {error.reason}\n".encode("ascii")
        writer.write((f"HTTP/1.1 {error.status} {error.reason}\r\n"
                      f"Content-Type: text/plain\r\nContent-Length: {len(body)}\r\n"
                      "Connection: close\r\n\r\n").encode("ascii") + body)
        try:
            await writer.drain()
        except ConnectionError:
            pass


def positive_int(name: str, default: int) -> int:
    try:
        value = int(os.getenv(name, str(default)))
    except ValueError as exc:
        raise RuntimeError(f"{name} must be an integer") from exc
    if value <= 0:
        raise RuntimeError(f"{name} must be positive")
    return value


async def run():
    policy = EgressPolicy(parse_allowed_hosts(os.getenv("TOKENSEA_ALLOWED_EGRESS_HOSTS", "")),
                          parse_allowed_ports(os.getenv("TOKENSEA_EGRESS_ALLOWED_PORTS", "80,443")))
    proxy = EgressProxy(policy,
                        connect_timeout=positive_int("TOKENSEA_EGRESS_CONNECT_TIMEOUT_SECONDS", 10),
                        idle_timeout=positive_int("TOKENSEA_EGRESS_IDLE_TIMEOUT_SECONDS", 300),
                        header_timeout=positive_int("TOKENSEA_EGRESS_HEADER_TIMEOUT_SECONDS", 10),
                        max_header_bytes=positive_int("TOKENSEA_EGRESS_MAX_HEADER_BYTES", 65536),
                        max_headers=positive_int("TOKENSEA_EGRESS_MAX_HEADERS", 100),
                        max_request_bytes=positive_int("TOKENSEA_EGRESS_MAX_REQUEST_BYTES", 268435456),
                        max_response_bytes=positive_int("TOKENSEA_EGRESS_MAX_RESPONSE_BYTES", 268435456))
    host = os.getenv("TOKENSEA_EGRESS_LISTEN_HOST", "0.0.0.0")
    port = positive_int("TOKENSEA_EGRESS_LISTEN_PORT", 18080)
    server = await asyncio.start_server(proxy.handle_client, host, port, limit=proxy.max_header_bytes + 1)
    LOGGER.info("proxy_started host=%s port=%s allowlisted_hosts=%s", host, port, len(policy.allowed_hosts))
    async with server:
        await server.serve_forever()


if __name__ == "__main__":
    asyncio.run(run())
