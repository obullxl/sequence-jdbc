#!/bin/bash

# 仓库准备：提前准备好
#cd ../
#rm -rf maven-repository
#git clone -b master https://github.com/obullxl/maven-repository.git maven-repository

# 本地打包
#cd ./sequence-jdbc
mvn clean && mvn deploy -Dmaven.test.skip=true

# 上传仓库
cd ./../maven-repository
git add --all
git commit -m 'Deploy sequence-jdbc JAR: https://github.com/obullxl/sequence-jdbc'
git push origin master

# 返回项目
cd ../sequence-jdbc

# Gitee刷新：人工刷新
open -a '/Applications/Microsoft Edge.app' https://gitee.com/obullxl/maven-repository
