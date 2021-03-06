package coursier.cli.params

import cats.data.{NonEmptyList, Validated, ValidatedNel}
import cats.implicits._
import coursier.cli.options.DependencyOptions
import coursier.cli.util.DeprecatedModuleRequirements
import coursier.core._
import coursier.parse.{DependencyParser, ModuleParser}

import scala.io.Source

final case class DependencyParams(
  exclude: Set[(Organization, ModuleName)],
  perModuleExclude: Map[String, Set[(Organization, ModuleName)]], // FIXME key should be Module
  intransitiveDependencies: Seq[(Dependency, Map[String, String])],
  sbtPluginDependencies: Seq[(Dependency, Map[String, String])],
  scaladexLookups: Seq[String],
  defaultConfiguration: Configuration
)

object DependencyParams {
  def apply(scalaVersion: String, options: DependencyOptions): ValidatedNel[String, DependencyParams] = {

    val excludeV =
      ModuleParser.modules(options.exclude, scalaVersion).either match {
        case Left(errors) =>
          Validated.invalidNel(
            s"Cannot parse excluded modules:\n" +
              errors
                .map("  " + _)
                .mkString("\n")
          )

        case Right(excludes0) =>
          val (excludesNoAttr, excludesWithAttr) = excludes0.partition(_.attributes.isEmpty)

          if (excludesWithAttr.isEmpty)
            Validated.validNel(
              excludesNoAttr
                .map(mod => (mod.organization, mod.name))
                .toSet
            )
          else
            Validated.invalidNel(
              s"Excluded modules with attributes not supported:\n" +
                excludesWithAttr
                  .map("  " + _)
                  .mkString("\n")
            )
      }

    val perModuleExcludeV =
      if (options.localExcludeFile.isEmpty)
        Validated.validNel(Map.empty[String, Set[(Organization, ModuleName)]])
      else {

        // meh, I/O

        val source = Source.fromFile(options.localExcludeFile) // default codec...
        val lines = try source.mkString.split("\n") finally source.close()

        lines
          .toList
          .traverse { str =>
            val parent_and_child = str.split("--")
            if (parent_and_child.length != 2)
              Validated.invalidNel(s"Failed to parse $str")
            else {
              val child_org_name = parent_and_child(1).split(":")
              if (child_org_name.length != 2)
                Validated.invalidNel(s"Failed to parse $child_org_name")
              else
                Validated.validNel((parent_and_child(0), (Organization(child_org_name(0)), ModuleName(child_org_name(1)))))
            }
          }
          .map { list =>
            list
              .groupBy(_._1)
              .mapValues(_.map(_._2).toSet)
              .iterator
              .toMap
          }
      }

    val moduleReqV = (excludeV, perModuleExcludeV).mapN {
      (exclude, perModuleExclude) =>
        DeprecatedModuleRequirements(exclude, perModuleExclude)
    }

    val intransitiveDependenciesV = moduleReqV
      .toEither
      .flatMap { moduleReq =>
        DependencyParser.dependenciesParams(
          options.intransitive,
          options.defaultConfiguration0,
          scalaVersion
        ).either match {
          case Left(e) =>
            Left(
              NonEmptyList.one(
                s"Cannot parse intransitive dependencies:\n" +
                  e.map("  " + _).mkString("\n")
              )
            )
          case Right(l) =>
            Right(
              moduleReq(l.map { case (d, p) => (d.copy(transitive = false), p) })
            )
        }
      }
      .toValidated

    val sbtPluginDependenciesV = moduleReqV
      .toEither
      .flatMap { moduleReq =>
        DependencyParser.dependenciesParams(
          options.sbtPlugin,
          options.defaultConfiguration0,
          scalaVersion
        ).either match {
          case Left(e) =>
            Left(
              NonEmptyList.one(
                s"Cannot parse sbt plugin dependencies:\n" +
                  e.map("  " + _).mkString("\n")
              )
            )

          case Right(Seq()) =>
            Right(Nil)

          case Right(l0) =>
            val defaults = {
              val sbtVer = options.sbtVersion.split('.') match {
                case Array("1", _, _) =>
                  // all sbt 1.x versions use 1.0 as short version
                  "1.0"
                case arr => arr.take(2).mkString(".")
              }
              Map(
                "scalaVersion" -> scalaVersion.split('.').take(2).mkString("."),
                "sbtVersion" -> sbtVer
              )
            }
            val l = l0.map {
              case (dep, params) =>
                val dep0 = dep.copy(
                  module = dep.module.copy(
                    attributes = defaults ++ dep.module.attributes // dependency specific attributes override the default values
                  )
                )
                (dep0, params)
            }
            Right(l)
        }
      }
      .toValidated

    val defaultConfiguration = Configuration(options.defaultConfiguration)

    val scaladexLookups = options
      .scaladex
      .map(_.trim)
      .filter(_.nonEmpty)

    (excludeV, perModuleExcludeV, intransitiveDependenciesV, sbtPluginDependenciesV).mapN {
      (exclude, perModuleExclude, intransitiveDependencies, sbtPluginDependencies) =>
        DependencyParams(
          exclude,
          perModuleExclude,
          intransitiveDependencies,
          sbtPluginDependencies,
          scaladexLookups,
          defaultConfiguration
        )
    }
  }
}
