Query highligh and word split for ElasticSearch
==================================

introduce new search api for this function, "_highlight".
This function is same as the "_search" API.

Build from source
=======================

requirements:
-------------
JDK1.7+, maven

Steps
-----

1. checkout the source code from gitlab.
2. move into the root folder, run ***mvn clean install*** .
3. get the jar from ***target*** folder.


INSTALL
========

1.  move to $ES_HOME/plugins folder.
2.  create a dir named search-plugin
3.  copy the jar "search-highlight-1.1.6.jar" into the "search-plugin" folder.
4.  restart the ES.