val catsVersion       = "2.1.0"
val catsEffectVersion = "2.1.2"
val fs2Version        = "2.2.1"
val scodecCoreVersion = "1.11.6"
val scodecCatsVersion = "1.0.0"
val scodecStreamVersion ="2.0.0"
val newtypeVersion    = "0.4.3"
val ip4sVersion       = "1.3.1-SNAPSHOT"
val bencVersion       = "0.7.1"
val log4catsVersion   = "1.0.1"
val scalacheckVersion = "1.14.1"
val munitVersion      = "0.7.5"
val logbackVersion        = "1.2.3"

lazy val root = (project in file("."))
  .settings(
    organization := "com.minosiants",
    name := "kademlia",
    scalaVersion := "2.13.1",
    scalacOptions ++= Seq("-Ymacro-annotations", "-Ywarn-unused", "-Yrangepos"),
    libraryDependencies ++= Seq(
      "org.typelevel"     %% "cats-core"        % catsVersion,
      "org.typelevel"     %% "cats-effect"      % catsEffectVersion,
      "co.fs2"            %% "fs2-core"         % fs2Version,
      "co.fs2"            %% "fs2-io"           % fs2Version,
      "org.scodec"        %% "scodec-core"      % scodecCoreVersion,
      "org.scodec"        %% "scodec-cats"      % scodecCatsVersion,
      "org.scodec" %% "scodec-stream" % scodecStreamVersion,
      "io.estatico"       %% "newtype"          % newtypeVersion,
      "com.comcast"       %% "ip4s-core"        % ip4sVersion,
      "com.minosiants"    %% "benc"             % bencVersion,
      "io.chrisdavenport" %% "log4cats-core"              % log4catsVersion,
      "io.chrisdavenport" %% "log4cats-slf4j"   % log4catsVersion,
      "ch.qos.logback"    % "logback-classic"             % logbackVersion,
      "org.scalacheck"    %% "scalacheck"       % scalacheckVersion % Test,
      "org.scalameta"     %% "munit-scalacheck" % munitVersion % Test,
      "org.scalameta"     %% "munit"            % munitVersion % Test
    ),
    testFrameworks += new TestFramework("munit.Framework"),
    addCompilerPlugin(scalafixSemanticdb),
    addCompilerPlugin("org.typelevel" %% "kind-projector"     % "0.10.3"),
    addCompilerPlugin("com.olegpy"    %% "better-monadic-for" % "0.3.1")
  )
  .settings(licenceSettings)
  .settings(releaseProcessSettings)

lazy val licenceSettings = Seq(
  organizationName := "Kaspar Minosiants",
  startYear := Some(2020),
  licenses += ("Apache-2.0", new URL(
    "https://www.apache.org/licenses/LICENSE-2.0.txt"
  ))
)

import ReleaseTransformations._
lazy val releaseProcessSettings = Seq(
  releaseIgnoreUntrackedFiles := true,
  releaseProcess := Seq[ReleaseStep](
    checkSnapshotDependencies,
    inquireVersions,
    runClean,
    runTest,
    setReleaseVersion,
    commitReleaseVersion,
    tagRelease,
    publishArtifacts,
    setNextVersion,
    commitNextVersion,
    pushChanges
  )
)
