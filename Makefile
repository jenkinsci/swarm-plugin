all: build install run

build:
	mvn clean package

install:
	rm -rf jenkins_home/plugins/*
	cp plugin/target/swarm.hpi jenkins_home/plugins
	
run:
	docker-compose up -d

