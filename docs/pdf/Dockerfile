# Self-contained image that renders docs/reference into a polished PDF, with
# real rendered tables and Mermaid diagrams.
#   * ReportLab    -> the PDF layout
#   * mermaid-cli  -> Mermaid blocks rendered to PNG (needs headless Chromium)
# reportlab and mermaid-cli are pinned; the Debian packages are not, so what pins
# those is the published image. CI pulls the image tagged with a hash of this file
# and puppeteer-config.json (./gradlew docsPdfImage) rather than rebuilding it, and
# the first image built for a hash is the one every later run renders with. Any
# edit here is a new hash and so a fresh build — which is also how to pick up a
# newer Chromium on purpose.
#
# The renderer and its tests are NOT copied in: every run mounts the repository at
# /work and runs them from there. Copied in, each edit to generate_pdf.py would be
# a new image to build and publish, for a script that needs none of the above.
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
# PYTHONDONTWRITEBYTECODE: the scripts run from the bind-mounted checkout, and a
# __pycache__ written there by the container would be owned by its user, not yours.
ENV PUPPETEER_SKIP_DOWNLOAD=1 \
    PUPPETEER_EXECUTABLE_PATH=/usr/bin/chromium \
    PYTHONDONTWRITEBYTECODE=1 \
    HOME=/tmp
RUN npm install -g @mermaid-js/mermaid-cli@11.4.2

COPY puppeteer-config.json /opt/puppeteer-config.json

WORKDIR /work

# Expects the repository mounted at /work.
# Args: <reference-dir> <output-pdf> (relative to /work)
ENTRYPOINT ["python", "/work/docs/pdf/generate_pdf.py"]
CMD ["docs/reference", "build/docs/reference.pdf"]
