package com.typesafe.sbt
package sbtghpages

import sbt._
import Keys._
import com.typesafe.sbt.SbtGit.GitKeys
import com.typesafe.sbt.git.GitRunner
import GitKeys.{gitBranch, gitRemoteRepo}
import com.typesafe.sbt.site.SitePlugin

// Plugin to make use of github pages.
object GhpagesPlugin extends AutoPlugin {
  override val trigger: PluginTrigger = noTrigger
  override val  requires: Plugins = SitePlugin && GitPlugin
  override lazy val globalSettings: Seq[Setting[_]] = ghpagesGlobalSettings
  override lazy val  projectSettings: Seq[Setting[_]] = ghpagesProjectSettings

  object autoImport extends GhpagesKeys
  import autoImport._

  // TODO - Add some sort of locking to the repository so only one thread accesses it at a time...

  def ghpagesGlobalSettings: Seq[Setting[_]] = Seq(
    ghpagesBranch := "gh-pages",
    ghpagesNoJekyll := true
  )

  def ghpagesProjectSettings: Seq[Setting[_]] = Seq(
    //example: gitRemoteRepo := "git@github.com:jsuereth/scala-arm.git",
    ghpagesRepository := {
      val buildHash: String =
        Hash.toHex(Hash.apply(sbt.Keys.thisProjectRef.value.build.toASCIIString))
      file(System.getProperty("user.home")) / ".sbt" / "ghpages" / buildHash /  organization.value / name.value
    },
    gitBranch in ghpagesUpdatedRepository := gitBranch.?.value getOrElse Some(ghpagesBranch.value),
    ghpagesUpdatedRepository := updatedRepo(ghpagesRepository, gitRemoteRepo, gitBranch in ghpagesUpdatedRepository).value,
    ghpagesPushSite := pushSiteTask.value,
    ghpagesPrivateMappings := (mappings in SitePlugin.autoImport.makeSite).value,
    ghpagesSynchLocal := synchLocalTask.value,
    ghpagesCleanSite := cleanSiteTask.value,
    includeFilter in ghpagesCleanSite := AllPassFilter,
    excludeFilter in ghpagesCleanSite := NothingFilter
  )

  private def updatedRepo(repo: SettingKey[File], remote: SettingKey[String], branch: SettingKey[Option[String]]) =
    Def.task {
      val local = repo.value
      val git = GitKeys.gitRunner.value
      val s = streams.value
      git.updated(remote = remote.value, cwd = local, branch = branch.value, log = s.log)
      local
    }

  private def synchLocalTask =
    Def.task {
      val mappings = ghpagesPrivateMappings.value
      val repo = ghpagesUpdatedRepository.value
      val s = streams.value
      val incl = (includeFilter in ghpagesCleanSite).value
      val excl = (excludeFilter in ghpagesCleanSite).value
      // TODO - an sbt.Synch with cache of previous mappings to make this more efficient. */
      val betterMappings = mappings map { case (file, target) => (file, repo / target) }
      // First, remove 'stale' files.
      cleanSiteForRealz(repo, GitKeys.gitRunner.value, s, incl, excl)
      // Now copy files.
      IO.copy(betterMappings)
      if(ghpagesNoJekyll.value) IO.touch(repo / ".nojekyll")
      repo
    }

  private def cleanSiteTask =
    Def.task {
      cleanSiteForRealz(ghpagesUpdatedRepository.value, GitKeys.gitRunner.value, streams.value, (includeFilter in ghpagesCleanSite).value, (excludeFilter in ghpagesCleanSite).value)
    }
  private def cleanSiteForRealz(dir: File, git: GitRunner, s: TaskStreams, incl: FileFilter, excl: FileFilter): Unit = {
    val toClean = IO.listFiles(dir)
        .filter(f ⇒ f.getName != ".git" && incl.accept(f) && !excl.accept(f)).map(_.getAbsolutePath).toList
    if(!toClean.isEmpty)
      git(("rm" :: "-r" :: "-f" :: "--ignore-unmatch" :: toClean) :_*)(dir, s.log)
    ()
  }

  val commitMessage = sys.env.getOrElse("SBT_GHPAGES_COMMIT_MESSAGE", "updated site")
  private def pushSiteTask =
    Def.task {
      val git = GitKeys.gitRunner.value
      val repo = ghpagesSynchLocal.value
      val s = streams.value
      git.commitAndPush(commitMessage)(repo, s.log)
    }

  /** TODO - Create ghpages in the first place if it doesn't exist.
      *$ cd /path/to/fancypants
      *$ git symbolic-ref HEAD refs/heads/gh-pages
      *$ rm .git/index
      *$ git clean -fdx
      *<copy api and documentation>
      *$ echo "My GitHub Page" > index.html
      *$ git add .
      *$ git commit -a -m "First pages commit"
      *$ git push origin gh-pages
   */
}
