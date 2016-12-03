FROM ubuntu

EXPOSE 8080

RUN apt-get update && apt-get install -y software-properties-common
RUN echo oracle-java8-installer shared/accepted-oracle-license-v1-1 select true | debconf-set-selections && \
    add-apt-repository -y ppa:webupd8team/java && \
    apt-get update && \
    apt-get install -y oracle-java8-installer && \
    rm -rf /var/lib/apt/lists/* && \
    rm -rf /var/cache/oracle-jdk8-installer

RUN apt-get update && apt-get install wget curl netcat -y

RUN wget -q -O - http://pkg.jenkins-ci.org/debian-stable/jenkins-ci.org.key | apt-key add -
RUN echo "deb http://pkg.jenkins-ci.org/debian-stable binary/" >> /etc/apt/sources.list && \
    apt-get update && \
    apt-get install jenkins -y


ENTRYPOINT ["su",  "-l",  "jenkins",  "-c",  "java -jar /usr/share/jenkins/jenkins.war"]
