lazy val commonSettings = Seq(
  organization := "org.hyperledger",
  version := "0.0.1",
  scalaVersion := "2.11.7",
  scalacOptions ++= Seq(
    "-feature",
    "-deprecation",
    "-unchecked",
    "-Xcheckinit",
    "-Xlint",
    "-Xverify",
    "-Yclosure-elim",
    "-Yinline",
    "-language:higherKinds",
    "-language:implicitConversions",
    "-language:existentials",
    "-language:postfixOps",
    "-Yno-adapted-args"
  ),
  resolvers ++= Seq(
    Resolver.mavenLocal,
    Resolver.sonatypeRepo("snapshots"),
    Resolver.sonatypeRepo("releases"),
    "Scalaz Bintray Repo" at "http://dl.bintray.com/scalaz/releases"
  )
)

lazy val root = (project in file("."))
  .settings(commonSettings)
  .settings(Seq(name := "hyperLedger-network"))
  .aggregate(core, network)

lazy val core = project
  .settings(commonSettings)
  .settings(Seq(
    name := "hyperLedger-network-core",
    libraryDependencies ++= Seq(
      "com.hyperledger" % "hyperLedger-server-core" % "2.0.0-SNAPSHOT",
      "org.bouncycastle"   % "bcprov-jdk15on" % "1.52",
      "org.scalaz"        %% "scalaz-core"    % "7.1.3",
      "org.scodec"        %% "scodec-bits"    % "1.0.9",
      "org.scodec"        %% "scodec-core"    % "1.8.1",
      "org.scodec"        %% "scodec-scalaz"  % "1.1.0",
      "org.scalatest"     %% "scalatest"      % "2.2.4"   % "test",
      "org.scalacheck"    %% "scalacheck"     % "1.12.4"  % "test"
    )
))

lazy val network = project
  .settings(commonSettings)
  .settings(Seq(
    libraryDependencies ++= Seq(
      "com.hyperledger" % "hyperLedger-server-core" % "2.0.0-SNAPSHOT",
      "com.typesafe.akka" %% "akka-stream-experimental" % "1.0",
      "com.typesafe.akka" %% "akka-slf4j" % "2.3.12",
      "com.typesafe.akka" %% "akka-agent" % "2.3.12",
      "ch.qos.logback"     % "logback-classic"% "1.1.3",
      "org.bouncycastle"   % "bcprov-jdk15on" % "1.52",
      "org.scalaz"        %% "scalaz-core"    % "7.1.3",
      "org.scodec"        %% "scodec-bits"    % "1.0.9",
      "org.scodec"        %% "scodec-core"    % "1.8.1",
      "org.scodec"        %% "scodec-scalaz"  % "1.1.0",
      "com.typesafe.akka" %% "akka-stream-testkit-experimental" % "1.0" % "test",
      "org.scalatest"     %% "scalatest"      % "2.2.4"   % "test",
      "org.scalacheck"    %% "scalacheck"     % "1.12.4"  % "test"
    )
  ))
  .dependsOn(core)
