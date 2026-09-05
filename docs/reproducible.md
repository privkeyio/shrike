# Reproducible builds

The contents of the `.tar.gz` and `.zip` release files can be rebuilt byte for byte from this repository, so you can confirm the published binaries were built from the source you can read.

The installer packages (`.msi`, `.deb` and `.dmg`) are not byte for byte reproducible, because the files they archive carry the time of the build. Verify those against the signed `SHA256SUMS` published with each release, as described in the [README](../README.md#releases). What a `.deb` installs can still be compared, see [below](#comparing-what-a-deb-installs).

## Reproducing a release

### Install Java

Because the release binaries bundle a Java runtime, the same version of Java must be installed to rebuild them. Shrike uses Eclipse Temurin 25.0.2+10, which is what the release workflow installs.

Note: Do not install Java using a system package manager (e.g. apt, dnf, rpm).
Linux packages replace the JDK's bundled `cacerts` file with a symlink to the system CA certificates, which differ from those in the release tarballs and will produce a non-reproducible build.

#### Java from the Adoptium GitHub repository

It is available for all supported platforms from [Eclipse Temurin 25.0.2+10](https://github.com/adoptium/temurin25-binaries/releases/tag/jdk-25.0.2%2B10).

For reference, the downloads are as follows:
- [Linux x64](https://github.com/adoptium/temurin25-binaries/releases/download/jdk-25.0.2%2B10/OpenJDK25U-jdk_x64_linux_hotspot_25.0.2_10.tar.gz)
- [Linux aarch64](https://github.com/adoptium/temurin25-binaries/releases/download/jdk-25.0.2%2B10/OpenJDK25U-jdk_aarch64_linux_hotspot_25.0.2_10.tar.gz)
- [MacOS x64](https://github.com/adoptium/temurin25-binaries/releases/download/jdk-25.0.2%2B10/OpenJDK25U-jdk_x64_mac_hotspot_25.0.2_10.tar.gz)
- [MacOS aarch64](https://github.com/adoptium/temurin25-binaries/releases/download/jdk-25.0.2%2B10/OpenJDK25U-jdk_aarch64_mac_hotspot_25.0.2_10.tar.gz)
- [Windows x64](https://github.com/adoptium/temurin25-binaries/releases/download/jdk-25.0.2%2B10/OpenJDK25U-jdk_x64_windows_hotspot_25.0.2_10.zip)

On Linux, extract the tarball and set `JAVA_HOME` to use it for the build:
```shell
tar -xzf OpenJDK25U-jdk_x64_linux_hotspot_25.0.2_10.tar.gz
export JAVA_HOME=$PWD/jdk-25.0.2+10
export PATH=$JAVA_HOME/bin:$PATH
```

#### Java from SDKMAN

An alternative option for all platforms is to use the [sdkman.io](https://sdkman.io/) package manager ([Git Bash for Windows](https://git-scm.com/download/win) is a good choice on that platform).
See the installation [instructions here](https://sdkman.io/install).
Once installed, run
```shell
sdk install java 25.0.2-tem
```

### Other requirements

Other packages may also be necessary to build depending on the platform. On Debian/Ubuntu systems:
```shell
sudo apt install -y rpm fakeroot binutils
```

### Building the binaries

First, assign a temporary variable in your shell for the specific release you want to build, as it is named on the [releases page](https://github.com/privkeyio/shrike/releases):

```shell
GIT_TAG="v2.5.5-blake2b.21"
```

The project can then be initially cloned as follows. `--recursive` matters, because the BLAKE2b work lives in the drongo submodule:

```shell
git clone --recursive --branch "${GIT_TAG}" https://github.com/privkeyio/shrike.git
```

If you already have the shrike repo cloned, fetch all new updates and checkout the release. For this, change into your local shrike folder and execute:

```shell
cd {yourPathToShrike}/shrike
git pull --recurse-submodules
git checkout "${GIT_TAG}"
```

Note - there is an additional step if you updated rather than initially cloned your repo at `GIT_TAG`.
This is due to the Git submodules which need to be checked out to the commit state they had at the time of the release.
Only then your build will be comparable to the provided one in the release section of GitHub.
To checkout the submodule to the correct commit for `GIT_TAG`, additionally run:

```shell
git submodule update --checkout
```

Thereafter, building should be straightforward. If not already done, change into the shrike folder and run:

```shell
cd {yourPathToShrike}/shrike  # if you aren't already in the shrike folder
./gradlew jpackage
```

The binaries (and installers) will be placed in the `build/jpackage` folder.

### Verifying the binaries are identical

Verify the built binaries against the released binaries at https://github.com/privkeyio/shrike/releases.

Note that you will be verifying the files in the `build/jpackage/Shrike` folder against either the `.tar.gz` or `.zip` releases.
Download either of these depending on your platform and extract the contents to a folder (in the following example, `/tmp`).
Then compare all of the folders and files recursively:

```shell
diff -r build/jpackage/Shrike /tmp/Shrike
```

This command should have no output indicating that the two folders (and all their contents) are identical.

The headless server build reproduces the same way. It is built with the headless flag and compared against `shrikeserver-<version>-<arch>.tar.gz`, which also extracts to a `Shrike` folder:

```shell
./gradlew -Djava.awt.headless=true clean jpackage
diff -r build/jpackage/Shrike /tmp/Shrike
```

### Comparing what a deb installs

The `.deb` file itself is not byte for byte reproducible, but its payload is. Unpack both the locally built package and the published one and compare the trees:

```shell
./repackage.sh   # the release workflow runs this after jpackage

mkdir -p /tmp/deb-local /tmp/deb-published
(cd /tmp/deb-local && ar x {yourPathToShrike}/shrike/build/jpackage/shrike_<version>_amd64.deb && tar xf data.tar.xz)
(cd /tmp/deb-published && ar x /path/to/downloaded/shrike_<version>_amd64.deb && tar xf data.tar.xz)

diff -r /tmp/deb-local/opt /tmp/deb-published/opt
```

This should have no output. Only the timestamps recorded in the archive differ, and they are the time each build ran.

If there is output, please open an issue with detailed instructions to reproduce, including build system platform.
