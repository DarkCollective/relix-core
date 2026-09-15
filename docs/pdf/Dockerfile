# Self-contained image that renders docs/reference into a polished PDF, with
# real rendered tables and Mermaid diagrams.
#   * ReportLab    -> the PDF layout
#   * mermaid-cli  -> Mermaid blocks rendered to PNG (needs headless Chromium)
# Pinned deps so the output is reproducible regardless of host.
FROM python:3.12-slim

# System deps:
#   fonts-dejavu  -> Greek/maths operators (σ ⋈ ∀ λ π …) in PDF *and* diagrams
#   chromium      -> headless browser mermaid-cli drives (system build, so
#                    puppeteer does not download its own)
#   nodejs/npm    -> to run mermaid-cli
RUN apt-get update \
    && apt-get install -y --no-install-recommends \
        fonts-dejavu fonts-symbola fontconfig \
        nodejs npm \
        chromium \
    && rm -rf /var/lib/apt/lists/*

RUN pip install --no-cache-dir "reportlab==4.2.5"

# Use the system Chromium; never let puppeteer fetch its own.
ENV PUPPETEER_SKIP_DOWNLOAD=1 \
    PUPPETEER_EXECUTABLE_PATH=/usr/bin/chromium \
    HOME=/tmp
RUN npm install -g @mermaid-js/mermaid-cli@11.4.2

COPY puppeteer-config.json /opt/puppeteer-config.json
COPY generate_pdf.py /opt/generate_pdf.py
COPY test_generate_pdf.py /opt/test_generate_pdf.py

WORKDIR /work

# Args: <reference-dir> <output-pdf> (relative to the mounted /work)
ENTRYPOINT ["python", "/opt/generate_pdf.py"]
CMD ["docs/reference", "build/docs/reference.pdf"]
