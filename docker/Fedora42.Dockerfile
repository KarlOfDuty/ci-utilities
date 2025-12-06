FROM fedora:42
RUN dnf install dotnet-sdk-10.0 rpm-build git systemd-rpm-macros tree which -y