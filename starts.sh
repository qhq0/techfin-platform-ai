#!/bin/bash
envfile=${HOME}/.bash_profile
echo '' >> ${envfile}
echo '# alias' >> ${envfile}
echo 'alias ll="ls -l"' >> ${envfile}
echo 'export LANG=zh_CN.UTF-8' >> ${envfile}
echo 'export SADIR=/home/ap/sa/sa' >> ${envfile}
echo 'export PATH=${SADIR}/bin:${PATH}' >> ${envfile}
echo 'export LD_LIBRARY_PATH=${SADIR}/lib64:${LD_LIBRARY_PATH}' >> ${envfile}
echo 'export HostName=000' >> ${envfile}

cat ${envfile}
source ${envfile}

# JVM 参数放在 -jar 之前才会被当作 JVM 参数；java 由 Dockerfile 的 PATH 指向 JDK17 bin，exec 让 java 成为 tini 直接子进程
exec java -Xms256m -Xmx512m -Dfile.encoding="utf-8" -jar /home/ap/kjjr_ai/techfin-controller-1.0.0.RELEASE.jar