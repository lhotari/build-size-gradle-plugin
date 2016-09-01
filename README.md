# build-size-gradle-plugin

Gradle plugin for generating JSON that shows build size and structure but doesn't reveal content. Names are masked.

## Usage

### Installing the plugin

Add plugin to build script
```
plugins {
  id "io.github.lhotari.buildsize" version "0.3"
}
```
Then run the `buildSize` task. It creates a JSON file `build/buildsizeinfo.json`.


Example of prepending the plugin to the `build.gradle` file
```
echo 'plugins { id "io.github.lhotari.buildsize" version "0.3" }' | cat - build.gradle > build.gradle.new
mv build.gradle.new build.gradle
```

#### Script plugin

The build file in this project has a task called `generateScriptPlugin`.
That task will create a single file `build/buildSize.gradle` that can be used in an existing Gradle build as a
script plugin (`apply from: 'buildSize.gradle'`). This might be useful in environments where there is a policy
to not use any external Gradle plugins.


### Creating `buildsizeinfo.json` file

The plugin adds a task called `buildSize` to the root project.
Running that task produces `build\buildsizeinfo.json`, which contains the information.