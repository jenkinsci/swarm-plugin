import jenkins.model.Jenkins
import hudson.security.SecurityRealm

def instance = Jenkins.getInstance()
instance.setSecurityRealm(SecurityRealm.NO_AUTHENTICATION)
instance.save()
