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

# JDK17 不识别 -XX:PermSize/-XX:MaxPermSize（JDK8 遗留参数），已移除；
# JVM 参数放在 -jar 之前才会被当作 JVM 参数；显式用 ${JAVA_HOME}/bin/java 钉死 JDK17，exec 让 java 成为 tini 直接子进程
exec ${JAVA_HOME}/bin/java -Xms256m -Xmx512m -Dfile.encoding="utf-8" -jar /home/ap/kjjr_ai/techfin-controller-1.0.0.RELEASE.jar