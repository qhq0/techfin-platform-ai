# pull images
# 基础镜像：eclipse-temurin:8-jdk（Ubuntu 22.04 底层 + JDK1.8，glibc，镜像内已预配 JAVA_HOME/PATH）
FROM eclipse-temurin:8-jdk
LABEL MAINTAINER="qiuhaouquan <qiuhaouquan.sz@ccb.com>"

# change to root user
USER root

# 安装 tini（原 kylin 镜像预装了 tini，temurin 镜像未预装，需手动补装，否则 ENTRYPOINT 启动失败）
# 安装 mariadb-client（提供 mysql/mariadb 命令，用于容器内脚本操作数据库，MySQL 8.0 协议兼容）
RUN set -eux && \
    apt-get update && \
    apt-get install -y --no-install-recommends \
        tini \
        mariadb-client \
        && \
    rm -rf /var/lib/apt/lists/*

# make tini effect
ENTRYPOINT ["tini", "--"]

# docker arg
ARG BASE_DIR=/home/ap
ARG AP_USER=kjjr_ai
ARG AP_GROUP=kjjr_ai
ARG GID=2000
ARG UID=2001
ARG APP_PKG=techfin-controller-1.0.0.RELEASE.jar


# root env
ENV AP_HOME=${BASE_DIR}/${AP_USER}

# docker mkdir...
RUN set -eux && \
    mkdir -p ${BASE_DIR} && \
    mkdir -p ${AP_HOME} && \
    groupadd -r -g ${GID} ${AP_GROUP} && \
    useradd -g ${AP_GROUP} --uid=${UID} -b ${BASE_DIR} ${AP_USER};

# SA config
#ENV LESSCHARSET=UTF-8
#ENV SADDR=/home/ap/kjjr_ai/share
#ENV LD_LIBRARY_PATH=${SADDR}/lib64:${LD_LIBRARY_PATH}
#ENV PATH=${SADDR}/bin:${PATH}
#ENV CLASSPATH=${SADDR}/lib64/secapi.jar:${CLASSPATH}

# JDK17：公共仓库无 jdk17 镜像，用项目目录下的 tar 包解压叠加，覆盖基础镜像自带 JDK8
ARG JDK_TARBALL=jdk-17.0.20-linux-x64.tar.gz
COPY ${JDK_TARBALL} /tmp/${JDK_TARBALL}
RUN set -eux && \
    mkdir -p /usr/local/jdk && \
    tar -xzf /tmp/${JDK_TARBALL} -C /usr/local/jdk && \
    rm -f /tmp/${JDK_TARBALL}

# 用 JDK17 覆盖：JAVA_HOME 指向 JDK17，PATH 前插 JDK17 的 bin（覆盖基础镜像 JDK8）
ENV JAVA_HOME=/usr/local/jdk/jdk-17.0.20+8 \
    PATH=/usr/local/jdk/jdk-17.0.20+8/bin:${PATH}

# Create logs...
RUN mkdir -p ${AP_HOME}/p8log && \
    chown -R ${AP_USER}:${AP_GROUP} ${AP_HOME}

# copy package
COPY $APP_PKG $AP_HOME/
RUN chown ${AP_USER}:${AP_GROUP} ${AP_HOME}/${APP_PKG}

# Copy start sh
ADD starts.sh /
RUN chmod u+x /starts.sh && \
    chown ${AP_USER}:${AP_GROUP} /starts.sh && \
    ls -lrt ${AP_HOME} && \
    ls -lrt /;

# change user
USER ${AP_USER}

# change workdir
WORKDIR ${AP_HOME}

EXPOSE 8080

CMD ["sh", "-c", "/starts.sh"]