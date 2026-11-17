ARG PYTHON_IMAGE=python:3.12-slim-bookworm
FROM ${PYTHON_IMAGE}
RUN apt-get update && apt-get install -y --no-install-recommends openssl ca-certificates \
    && rm -rf /var/lib/apt/lists/*
ENV PYTHONDONTWRITEBYTECODE=1 PYTHONUNBUFFERED=1 LANG=C.UTF-8
WORKDIR /opt/callnexus-installer
COPY legacy/ ./legacy/
COPY installer.py ./installer.py
ENTRYPOINT ["python3", "-B", "/opt/callnexus-installer/installer.py"]
CMD ["help"]
