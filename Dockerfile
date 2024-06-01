# Use an official Maven image as a build stage
FROM maven:3.8.3-openjdk-17 AS build

# Set the working directory in the container
WORKDIR /app

# Copy the pom.xml and the source code to the container
COPY pom.xml .
COPY src ./src

RUN mvn dependency:go-offline

RUN mvn package -DskipTests

# Use an official OpenJDK runtime image as a base image
FROM openjdk:17-jdk

# Set the working directory in the container
WORKDIR /app

# Copy the packaged jar file from the build stage
COPY --from=build /app/target/*.jar app.jar

# Expose the port the application runs on
EXPOSE 9098

# Set the startup command to run the jar
CMD ["java", "-jar", "app.jar"]


