# build-size-gradle-plugin

Gradle plugin for generating JSON that shows build size and structure but doesn't reveal content. Names are masked.

### Usage

Add plugin to build script
```
plugins {
  id "io.github.lhotari.buildsize" version "0.1"
}
```
Then run the `buildSize` task. It creates a JSON file `build/buildsizeinfo.json`.


Example of prepending the plugin to the `build.gradle` file
```
echo 'plugins { id "io.github.lhotari.buildsize" version "0.1" }' | cat - build.gradle > build.gradle.new
mv build.gradle.new build.gradle
```