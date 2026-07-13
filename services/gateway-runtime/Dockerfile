
FROM python:3.12-slim
WORKDIR /app
COPY requirements.txt ./
RUN pip install --no-cache-dir -r requirements.txt
COPY app ./app
RUN groupadd --gid 10001 tokensea \
    && useradd --uid 10001 --gid 10001 --no-create-home --shell /usr/sbin/nologin tokensea \
    && install -d -o 10001 -g 10001 -m 0700 /var/lib/tokensea-gateway/outbox
USER 10001:10001
EXPOSE 39212
CMD ["uvicorn", "app.main:app", "--host", "0.0.0.0", "--port", "39212"]
