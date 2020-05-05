val catsVersion           = "2.1.0"
val catsEffectVersion     = "2.1.2"
val specs2Version         = "4.8.3"
val logbackVersion        = "1.2.3"
val scalacheckVersion     = "1.14.1"
val catsEffectTestVersion = "0.3.0"

lazy val root = (project in file("."))
  .settings(
    organization := "com.minosiants",
    name := "kademlia",
    scalaVersion := "2.13.1",
    libraryDependencies ++= Seq(
      "org.typelevel"   %% "cats-core"                  % catsVersion,
      "org.typelevel"   %% "cats-effect"                % catsEffectVersion,
      "org.scalacheck"  %% "scalacheck"                 % scalacheckVersion % "test",
      "com.codecommit"  %% "cats-effect-testing-specs2" % catsEffectTestVersion % "test",
      "ch.qos.logback"  % "logback-classic"             % logbackVersion
    ),
    addCompilerPlugin("org.typelevel" %% "kind-projector" % "0.10.3")
  ).settings(licenceSettings)
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
  releaseProcess := Seq[ReleaseStep](checkSnapshotDependencies,
    inquireVersions,
    runClean,
    runTest,
    setReleaseVersion,
    commitReleaseVersion,
    tagRelease,
    publishArtifacts,
    setNextVersion,
    commitNextVersion,
    pushChanges))



