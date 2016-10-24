# tdd-maven-plugin
A maven plugin with a file watcher for TDD runs

As this plugin is not in the official maven plugin repositories, you have to check it out locally then install it with 
```bash
mvn install
```
Then you should put it into your pom.xml's plugins section:


```xml
<build>
 <plugins>
 ...
   <plugin>
		<groupId>hu.letscode</groupId>
			<artifactId>tdd-maven-plugin</artifactId>
			<version>0.0.1-SNAPSHOT</version>
			<configuration>
				<watches>
					<watch>
						<directory>src/main/java</directory>
					</watch>
					<watch>
						<directory>src/test/java</directory>
					</watch>
				</watches>
			</configuration>
  </plugin>
 ...
 <plugins>
<build>
```

After that call it with:

```bash
mvn tdd:run
```

It starts watching for file changes and calls the maven test phase, passing target files accordingly.
