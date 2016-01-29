## Memorandum of Understanding
This project has specific intentions and guidelines that are provided here. If you do not agree with the provisions in the [MOU](MOU.md), contact the Digital Asset Holdings and do not proceed with accessing, reading or contributing to this project.

# Hyperledger
Hyperledger is an enterprise ready blockchain server with a client [API](docs/api.md). Hyperledger has a modular [architecture](docs/architecture.md) and [configurable](docs/configuration.md) network architecture.

Hyperledger implements an append-only log of financial transactions designed to be replicated at multiple organizations without centralized control. Technology companies today are well aware of the benefits of having a data backbone, or Enterprise Service Bus, to coordinate data across services within the company. The goal of Hyperledger is to allow expansion of the data backbone concept to the multi-organization level.

Hyperledger is designed to be the lowest level communication and consensus layer and aims to upgrade components of today's global financial infrastructure, which consists of a complex patchwork of systems, protocols, and adapters. We are open sourcing this project with the belief that as a critical part of the new financial infrastructure, this part of the software stack should be commoditized, collaborated on and serve as the robust backbone on which applications can be developed.

What we are making available today is the most recent stable version of a combination of many man years of work across multiple startups: Digital Asset, Bits of Proof, Blockstack, and Hyperledger. However, it is still a work in progress and we are in the process of replacing several components, adding others, and integrating with other open source projects. This particularly relates to security, scalability, and privacy, and is outlined in the roadmap below.

Hyperledger was built with the requirements of enterprise architecture in mind by a team that has worked in financial institutions for decades. It has a highly modular design at both the code and runtime levels to allow for integrations with legacy systems. The networking rules are configurable to allow for distinct interoperable consensus groups, each with its own functional and nonfunctional requirements.

Hyperledger utilizes the same UTXO/script based transactional decision of Bitcoin and extends it with features required in financial services. While the public Bitcoin blockchain is not suitable for many uses within regulated financial infrastructure, much of its design and mature cryptography has been withstanding attacks in the wild, protecting tokens with a market cap in the billions of dollars. There has been a large amount of venture investment around Bitcoin and a huge body of development work done around it. By conforming to the UTXO model as a de facto standard there is a larger ecosystem of innovation to draw from.

Digital Asset has also started working on a prototype implementation of the [Practical Byzantine Fault Tolerance](docs/pbft.md) consensus module as a replacement for Proof of Work. We are collaborating with many of the other members of the project on the consensus module to ensure there is a scalable, secure, Byzantine Fault Tolerant consensus protocol that can provide settlement finality for wholesale financial institutions.

Other major additions are:
  * [Signed blocks](docs/blocksignature.md)
  * [Multiple native assets](docs/nativeassets.md)

## Roadmap

After being submitted to the Linux Foundation’s Hyperledger project, the roadmap will be determined by the Technical Steering Committee and community of contributions. The code base that we are contributing is the latest stable version but we have many more improvements that we will be contributing shortly:

  * Enhanced privacy features
  * Integration with multiple Byzantine Fault Tolerant consensus implementations
  * JNA bindings to a consensus library derived from the Bitcoin consensus library. This will allow for security audit of the patchset defining the divergence from Bitcoin, which has a well understood set of consensus rules
  * Fixes following a 3rd party security audit and static code analysis
  * Support for some of the [Elements Project](docs/elements.md) feature set

## Building and running

### Prerequisites
Version numbers below indicate the versions used.

 * Git 2.4.6 (http://git-scm.com)
 * Maven 3.3.3 (http://maven.apache.org)
 * Java 1.8.0_51 (http://java.oracle.com)
 * JCE 8 (Java Crptography Extension) (http://java.oracle.com)
 * Protobuf compiler 2.5.0 (http://github.com/google/protobuf)
 * lockfile command 3.22 (from procmail package)

#### Optionally a JMS bus provider
 * e.g. Apache ActiveMQ 5.11.1 (http://activemq.apache.org/)

#### Installing Prerequisites on OSX
 * ```brew update```
 * ```brew tap homebrew/versions```
 * ```brew install git```
 * ```brew install maven```
 * Download and install the latest Java 8 dmg file from Oracle
 * Download _Java Cryptography Extension (JCE) Unlimited Strength Jurisdiction Policy Files for JDK/JRE 8_ from Oracle, which is a zip file. Extract it and copy the `local_policy.jar` and `US_export_policy.jar` files to your your `<java_runtime_home>/lib/security`
 * ```brew install protobuf250```
 * ```brew install procmail``` if the command ```lockfile``` is not available on your OSX version
 
#### Installing Prerequisites on Ubuntu Linux
 * ```add-apt-repository ppa:webupd8team/java```
 * ```apt-get update```
 * ```apt-get install git maven oracle-java8-installer oracle-java8-unlimited-jce-policy protobuf-compiler procmail```
 
### Building Steps

 * ```git clone ???```
 * ```cd hyperledger```
 * ```mvn clean package```

The most important results of the build are 
 * _server/main/target/hyperledger-server-main-\<version\>-dist.tar.gz_ - for bitcoin-like network 
 * _server/pbft/target/hyperledger-server-pbft-\<version\>-dist.tar.gz_ - for PBFT network

## Running

### Setup

It is recommended to copy the resulted *.tar.gz file outside of the project directory and execute the below commands there. The below commands are illustrating running the bitcoin-like network, but the similar steps apply for the other one too.

 * ```tar zxf hyperledger-server-main-<version>-dist.tar.gz```
 * ```cd hyperledger-server-main-<version>```

#### Setup for server and client in two processes connected with ActiveMQ

 * Besides the above steps copy _examples/jmsclient/target/hyperledger-examples-jmsclient-\<version\>.jar_ next to the extracted _hyperledger-server-main-\<version\>_ directory
 * Copy from the extracted _hyperledger-server-main-\<version\>/conf/activemq.xml_ to the _conf_ directory of your ActiveMQ installation

### Starting and stopping 
 
 * ```./start.sh conf/bitcoin1.conf```
 * ```./stop.sh```
 
The start script accepts a config file parameter. Example config files are in the _conf_ directory. If no parameter is provided then it defaults to the system property _hyperledger.configurationFile_ if it is set, otherwise it defaults to _conf/application.conf_.

Only one instance can run from a directory. If you want to run multiple instances then you have to repeat the setup steps in another location. This is because working files are saved in this directory.

You can change the config files for your needs, details are described [here](docs/configuration.md). Also you can tune the _conf/logback.xml_ file to adjust what and how to log. 

#### Starting and stopping server and client in two processes connected with ActiveMQ

 * ```./bin/activemq start``` from the directory of your ActiveMQ
 * ```./start.sh conf/production_with_jms.conf``` from the extracted _hyperledger-server-main-\<version\>_ directory
 * ```java -cp hyperledger-server-main-<version>/hyperledger-server-main-<version>-shaded.jar:hyperledger-examples-jmsclient-<version>.jar Main```

It will print out that _There are 5000000000 satoshis on the genesis address_.

 * ```./stop.sh``` from the extracted _hyperledger-server-main-\<version\>_ directory
 * ```./bin/activemq stop``` from the directory of your ActiveMQ

### Working files

Database is saved into the _data_ directory if LevelDB is configured for storage. (Memory data store is never persisted.) If you want a clean start then remove the entire data directory.

Logs are stored in the _logs_ directory. The actual log file is rolled and compressed in every hour. Oldest ones are deleted if there are too many of them.

Start and stop scripts use _PID.LOCK_ file. Do not remove it manually because then the stop script will not know which process to stop. If it is removed accidentally then you have to use ```kill``` to terminate the process.


## Documentation
 * [Architecture](docs/architecture.md)
 * [Configuration](docs/configuration.md)
 * [API](docs/api.md) (low level API)
 * [Account](docs/accountmodule.md) (high level API)

## Contributing
[How to contribute?](docs/contributing.md)
[Hyperledger Mailing List](https://groups.google.com/a/digitalasset.com/forum/?hl=en#!forum/hyperledger)