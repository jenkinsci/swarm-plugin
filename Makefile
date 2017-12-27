all: build install run

build:
	mvn clean package

install:
	rm -rf jenkins_home/plugins/swarm
	cp plugin/target/swarm.hpi jenkins_home/plugins

run:
#	echo 2.0 > jenkins_home/jenkins.install.UpgradeWizard.state
	echo -n 2.0 > jenkins_home/jenkins.install.InstallUtil.lastExecVersion
	docker-compose up -d

stop:
	docker-compose stop

clean: stop
	find jenkins_home -type f ! -name '.keepit' ! -name 'configure-security.groovy' -exec rm -f {} \;
	find jenkins_home -depth -mindepth 1 -type d ! -name 'init.groovy.d' ! -name 'plugins' -exec rm -rf {} \;

deep-clean: clean
	docker-compose down
