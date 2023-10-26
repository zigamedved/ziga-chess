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

# Copy the indexedFiles directory containing files your server reads
COPY indexedFiles /app/indexedFiles

EXPOSE 8080

RUN echo "APP_PREFIX: $APP_PREFIX"

# Set the entry point to run the Java application
CMD ["java", "-jar", "/app/target/my-maven-docker-project.jar"]
