FROM python:3.12-slim

WORKDIR /app
COPY requirements.txt .
RUN pip install --no-cache-dir -r requirements.txt

COPY main.py .

EXPOSE 8001

ENV MCP_BEARER_TOKEN=""
ENV DB_PATH="/tmp/cvp_demo.db"
ENV REMOTE_MCP_SERVERS="[]"

CMD ["uvicorn", "main:app", "--host", "0.0.0.0", "--port", "8001"]
