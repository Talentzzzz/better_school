# ES 8.13.4 + IK 中文分词插件（版本必须与 ES 严格一致）
# 插件包托管于 release.infinilabs.com（infinilabs/analysis-ik，GitHub 不再托管插件包）
FROM docker.elastic.co/elasticsearch/elasticsearch:8.13.4

RUN bin/elasticsearch-plugin install --batch https://release.infinilabs.com/analysis-ik/stable/elasticsearch-analysis-ik-8.13.4.zip
