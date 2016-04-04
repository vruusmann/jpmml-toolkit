JPMML-Toolkit
=============

Java command-line applications for working with PMML documents.

# Prerequisites #

* Java 1.7 or newer.

# Installation #

Enter the project root directory and build using [Apache Maven] (http://maven.apache.org/):
```
mvn clean install
```

The build produces an executable uber-JAR file `target/toolkit-executable-1.0-SNAPSHOT.jar`.

# Usage #

### Model aggregation

Creating a weighted average of three PMML documents `global.pmml`, `regional.pmml` and `local.pmml`:
```
java -cp target/toolkit-executable-1.0-SNAPSHOT.jar org.jpmml.toolkit.Aggregator --input global.pmml,regional.pmml,local.pmml --weights 0.5,0.25,0.25 --output aggregate.pmml
```

# License #

JPMML-Toolkit is licensed under the [GNU Affero General Public License (AGPL) version 3.0] (http://www.gnu.org/licenses/agpl-3.0.html). Other licenses are available on request.

# Additional information #

Please contact [info@openscoring.io] (mailto:info@openscoring.io)
