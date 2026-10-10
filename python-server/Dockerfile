FROM public.ecr.aws/docker/library/python:3.9

WORKDIR /app

COPY engine/ /app/engine/
COPY main.py /app/
COPY requirements.txt /app/

RUN pip install --no-cache-dir -r requirements.txt

ARG PREFIX
ENV APP_PREFIX=$PREFIX
# Required at runtime: AUTH_USERNAME, AUTH_PASSWORD
# Optional: JAVA_SERVICE_URL (default http://127.0.0.1:8080/position)

# main.py binds to 8081 (java-server uses 8080). EXPOSE was previously 8080 by
# mistake, which mismatched the process listen port and confused port mapping.
EXPOSE 8081
CMD ["python", "main.py"]