FROM python:3.12-alpine

RUN addgroup -S -g 10001 tokensea && adduser -S -D -H -u 10001 -G tokensea tokensea
WORKDIR /app
COPY app ./app

USER 10001:10001
EXPOSE 18080
CMD ["python", "-m", "app.proxy"]
