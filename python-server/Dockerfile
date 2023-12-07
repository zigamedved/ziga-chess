FROM public.ecr.aws/docker/library/python:3.9

WORKDIR /app

COPY engine/ /app/engine/
COPY main.py /app/
COPY requirements.txt /app/

RUN pip install --no-cache-dir -r requirements.txt

ARG PREFIX
ENV APP_PREFIX=$PREFIX

EXPOSE 8080
CMD ["python", "main.py"]