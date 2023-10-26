FROM public.ecr.aws/docker/library/python:3.9

# Set the working directory inside the container
WORKDIR /app

# Copy the entire engine directory into the container's /app directory
COPY engine/ /app/engine/

# Copy the rest of your application files
COPY main.py /app/
COPY requirements.txt /app/

RUN pip install --no-cache-dir -r requirements.txt

ARG PREFIX
ENV APP_PREFIX=$PREFIX

EXPOSE 8080
CMD ["python", "main.py"]