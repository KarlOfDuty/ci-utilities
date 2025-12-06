def prepare_gpg_key()
{
  withCredentials([string(credentialsId: 'JENKINS_GPG_KEY_PASSWORD', variable: 'JENKINS_GPG_KEY_PASSWORD')])
  {
    exitCode = sh(script: '/usr/lib/gnupg/gpg-preset-passphrase --passphrase "$JENKINS_GPG_KEY_PASSWORD" --preset 0D27E4CD885E9DD79C252E825F70A1590922C51E', returnStatus: true)
    if (exitCode == 2) {
      sh 'gpg-connect-agent reloadagent /bye'
      sh '/usr/lib/gnupg/gpg-preset-passphrase --passphrase "$JENKINS_GPG_KEY_PASSWORD" --preset 0D27E4CD885E9DD79C252E825F70A1590922C51E'
    }
  }
}

def build_rpm_package(String distro, String spec_file, String package_name, String extra_build_args = "")
{
  sh "mkdir -p ${distro}/SOURCES"
  sh "git archive --format=tar.gz HEAD > '${distro}/SOURCES/rpm-source.tar.gz'"
  sh "rpmbuild -ba ${spec_file} --define \"_topdir ${WORKSPACE}/${distro}\" --define 'distro ${distro}' ${extra_build_args}"
  sh "cp ${distro}/RPMS/x86_64/${package_name}-*.x86_64.rpm ${distro}/"
  sh "cp ${distro}/SRPMS/${package_name}-*.src.rpm ${distro}/"
}

// The deb packages are a bit too complicated to make a standardised build function for,
// each repo will have to use their own script.

def sign_rpm_package(String rpm_path)
{
  sh "rpmsign --define '_gpg_name Karl Essinger (Jenkins Signing) <xkaess22@gmail.com>' --addsign ${rpm_path}"
  sh "rpm -vv --checksig ${rpm_path}"
}

def sign_deb_package(String deb_path, String dsc_path)
{
  sh "cat ${dsc_path} | gpg -u 2FEAAE97C813C486 --clearsign > ${dsc_path}"
  sh "gpg --verify ${dsc_path}"
  sh "/usr/bin/site_perl/debsigs --sign=origin -k 2FEAAE97C813C486 ${deb_path}"
  sh "debsig-verify ${deb_path}"
}

def publish_rpm_package(String distro_dir, String rpm_path, String srpm_path, String package_name)
{
  def repo_dir="/usr/share/nginx/repo.karlofduty.com/${distro_dir}"
  def package_dir="${repo_dir}/x86_64/Packages/${package_name}"
  def source_dir="${repo_dir}/source/Packages/${package_name}"

  sh "mkdir -p ${package_dir}"
  sh "mkdir -p ${source_dir}"

  sh "cp ${rpm_path} ${package_dir}"
  sh "cp ${srpm_path} ${source_dir}"

  sh "createrepo_c --update ${repo_dir}/x86_64"
  sh "createrepo_c --update ${repo_dir}/source"
}

def publish_deb_package(String distro, String package_name, String package_dir, String build_root, String component)
{
  def repo_dir="/usr/share/nginx/repo.karlofduty.com/${distro}"
  def pool_dir="${repo_dir}/pool/${component}/${package_dir}"
  def dists_bin_dir="${repo_dir}/dists/${distro}/${component}/binary-amd64"
  def dists_src_dir="${repo_dir}/dists/${distro}/${component}/source"

  // Copy package and sources to pool directory
  sh "mkdir -p ${pool_dir}"
  sh "cp ${build_root}/${package_name}_*_amd64.deb ${pool_dir}"
  sh "cp ${build_root}/${package_name}_*.tar.xz ${pool_dir}"
  sh "cp ${build_root}/${package_name}_*.dsc ${pool_dir}"
  dir("${repo_dir}")
  {
    // Generate package lists
    sh "mkdir -p ${dists_bin_dir}"
    sh "dpkg-scanpackages --arch amd64 -m pool/${component} > ${dists_bin_dir}/Packages"
    sh "cat ${dists_bin_dir}/Packages | gzip -9c > ${dists_bin_dir}/Packages.gz"

    // Generate source lists
    sh "mkdir -p ${dists_src_dir}"
    sh "dpkg-scansources pool/ > ${dists_src_dir}/Sources"
    sh "cat ${dists_src_dir}/Sources | gzip -9c > ${dists_src_dir}/Sources.gz"
  }
  sh """
    if [ -d "${repo_dir}@tmp" ];
    then
      rmdir "${repo_dir}@tmp"
    fi
  """
}

def generate_debian_release_file(String ci_root, String distro)
{
  def repo_dir="/usr/share/nginx/repo.karlofduty.com/${distro}"

  dir("${repo_dir}/dists/${distro}")
  {
    sh "DIST=${distro} ${ci_root}/scripts/generate-deb-release-file.sh > Release"
    sh "cat Release | gpg -u 2FEAAE97C813C486 -abs > Release.gpg"
    sh "gpg --verify Release.gpg Release"
    sh "cat Release | gpg -u 2FEAAE97C813C486 --clearsign > InRelease"
    sh "gpg --verify InRelease"
  }
  sh """
    if [ -d "${repo_dir}/dists/${distro}@tmp" ];
    then
      rmdir "${repo_dir}/dists/${distro}@tmp"
    fi
  """
}

def update_aur_git_package(String package_name, String pkgbuild_path, String install_file_path)
{
  sh "git clone -c init.defaultBranch=master ssh://aur@aur.archlinux.org/${package_name}.git .tmp-${package_name}-aur"
  dir(".tmp-${package_name}-aur")
  {
    sh "rm PKGBUILD"
    sh "cp ../${pkgbuild_path} ./PKGBUILD"
    sh "cp ../${install_file_path} ./"
    sh "makepkg --nobuild && makepkg --printsrcinfo > .SRCINFO"
    sh "git commit -am 'Jenkins automated version bump'"
    sh "git push"
  }
}

def verify_release_does_not_exist(String repo, String release_version)
{
  withCredentials([usernamePassword(credentialsId: 'karlofduty_github', usernameVariable: 'GH_USER', passwordVariable: 'GH_TOKEN')]) {
    def result = sh(
      script: "gh release view ${release_version} --repo ${repo} > /dev/null 2>&1",
      returnStatus: true
    )

    if (result == 0)
    {
      error "Release '${release_version}' already exists in '${repo}'"
    }
  }
}

def create_github_release(String repo, String release_version, List<String> artifacts, Boolean is_prerelease)
{
  def artifacts_str = artifacts.collect { "\"${it}\"" }.join(' ')
  def prerelease_arg = is_prerelease ? '--prerelease' : ''
  def release_title = ""

  if (release_version.toUpperCase().contains("RC"))
  {
    release_title = "Release Candidate ${release_version}"
  }
  else if (is_prerelease)
  {
    release_title = "Pre-release ${release_version}"
  }
  else
  {
    release_title = "Release ${release_version}"
  }

  withCredentials([usernamePassword(credentialsId: 'karlofduty_github', usernameVariable: 'GH_USER', passwordVariable: 'GH_TOKEN')]) {
    sh """
      gh release create ${release_version} ${artifacts_str} \\
        --title "${release_title}" \\
        --notes "This description will be replaced with a proper changelog shortly." \\
        --repo ${repo} \\
        ${prerelease_arg}
    """
  }
}

return this