FROM digitalasset/da-java:6

RUN mkdir -p /hyperledger/server
WORKDIR /hyperledger/server
COPY network/target/hyperledger-server-network-2.0.0-SNAPSHOT-dist.tar.gz hyperledger-server-network-2.0.0-SNAPSHOT-dist.tar.gz

RUN tar xvf hyperledger-server-network-2.0.0-SNAPSHOT-dist.tar.gz
WORKDIR /hyperledger/server/hyperledger-server-network-2.0.0-SNAPSHOT
COPY docker/application.conf conf/application.conf

CMD JMS_HOST=`cat /etc/hosts | grep jms | awk 'NR==1 {print $1}'` BITCOIND_HOST=`cat /etc/hosts | grep bitcoind | awk 'NR==1 {print $1}'` /usr/bin/java -jar hyperledger-server-network-2.0.0-SNAPSHOT-shaded.jar
