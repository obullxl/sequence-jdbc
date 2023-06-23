#!/bin/bash

# 创建目录
rm -rf ./../repository
mkdir ./../repository

# 仓库准备
cd ./../repository
git clone -b master https://gitee.com/obullxl/maven-repository.git sequence-jdbc

# 本地打包
cd ../sequence-jdbc
mvn clean
mvn deploy -Dmaven.test.skip=true

# 上传仓库
cd ./../repository/sequence-jdbc
git add *
git commit -m 'Deploy sequence-jdbc JAR: https://gitee.com/obullxl/sequence-jdbc'
git push origin master

# 返回项目
cd ../../sequence-jdbc
