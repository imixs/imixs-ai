FROM quay.io/wildfly/wildfly:29.0.1.Final-jdk17

LABEL description="Imixs-AI RAG-Index"
LABEL maintainer="ralph.soika@imixs.com"

# Deploy artefact
ADD ./target/imixs-ai-rag-index.war /opt/jboss/wildfly/standalone/deployments/

# Run with management interface
CMD ["/opt/jboss/wildfly/bin/standalone.sh", "-b", "0.0.0.0", "-bmanagement", "0.0.0.0"]   