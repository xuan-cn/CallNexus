FROM alpine:3.21

RUN apk add --no-cache bash curl ffmpeg jq ca-certificates
COPY media-agent.sh /usr/local/bin/media-agent
RUN chmod +x /usr/local/bin/media-agent

ENTRYPOINT ["/usr/local/bin/media-agent"]
