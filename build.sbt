ThisBuild / scalaVersion := "3.3.3"
ThisBuild / version := "0.1.0-SNAPSHOT"
ThisBuild / organization := "io.foldables"
ThisBuild / organizationName := "foldables"

lazy val core = (project in file("modules/core"))
  .settings(
    name := "conductor",
    libraryDependencies ++= Seq(
      "com.disneystreaming.smithy4s" %% "smithy4s-http4s" % smithy4sVersion.value,
      "com.disneystreaming.smithy4s" %% "smithy4s-http4s-swagger" % smithy4sVersion.value,
      "org.http4s" %% "http4s-ember-server" % "0.23.26"
    ),
    Compile / run / fork := true,
    Compile / run / connectInput := true
  )

lazy val example = (project in file("example"))
  .dependsOn(core)
  .enablePlugins(Smithy4sCodegenPlugin)
