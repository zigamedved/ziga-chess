# Use the official Maven image to build your application
FROM public.ecr.aws/docker/library/maven:3.9-amazoncorretto-17

# Set the desired prefix as a build argument
ARG PREFIX

# Set the working directory in the container
WORKDIR /app

# Copy the source code into the container
COPY . /app/

ENV APP_PREFIX=$PREFIX

# Build the application using Maven
RUN mvn clean package

ENV APP_PREFIX=$PREFIX

# Set the working directory in the container
WORKDIR /app

# Lucene index is not copied into the image — build with `make index` and mount
# the directory, or set LUCENE_INDEX_DIR to a prebuilt path.
# Example: docker run -v "$PWD/java-server/indexedFiles:/app/indexedFiles" ...

EXPOSE 8080

RUN echo "APP_PREFIX: $APP_PREFIX"

# Required at runtime: AUTH_USERNAME, AUTH_PASSWORD
# Optional: LUCENE_INDEX_DIR (default ./indexedFiles), LUCENE_TOP_K (default 50)
CMD ["java", "-jar", "/app/target/my-maven-docker-project.jar"]
