FROM python:3.12-slim
WORKDIR /app
COPY relay_server.py .
EXPOSE 9000
USER nobody
CMD ["python3", "relay_server.py", "9000"]
