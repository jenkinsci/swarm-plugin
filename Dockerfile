FROM ubuntu
RUN apt-get update && apt-get install -y software-properties-common
RUN echo oracle-java8-installer shared/accepted-oracle-license-v1-1 select true | debconf-set-selections && \
    add-apt-repository -y ppa:webupd8team/java && \
    apt-get update && \
    apt-get install -y oracle-java8-installer && \
    apt-get install -y curl && \
    rm -rf /var/lib/apt/lists/* && \
    rm -rf /var/cache/oracle-jdk8-installer
RUN curl -o http://jenkins:8080/swarm/swarm-client.jar
ENTRYPOINT ["java", "-jar", "swarm-client.jar", "-master", "http://jenkins:8080/"]
