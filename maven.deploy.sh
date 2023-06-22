#!/bin/bash

# 创建目录
rm -rf ./../repository/sequence-jdbc
mkdir -p ./../repository

# 远程准备
cd ./../repository
git clone -b sequence-jdbc https://gitee.com/obullxl/maven-repository.git sequence-jdbc

# 本地打包
cd ./../sequence-jdbc
mvn clean
mvn deploy -Dmaven.test.skip=true

# 上传仓库
cd ./../repository/sequence-jdbc
git add *
git commit -m 'Deploy sequence-jdbc: https://gitee.com/obullxl/sequence-jdbc'
git push origin sequence-jdbc:sequence-jdbc

# 返回项目
cd ./../sequence-jdbc
