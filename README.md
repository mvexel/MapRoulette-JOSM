# Usage
## Download MapRoulette Tasks
There are two methods to download tasks from MapRoulette.
1. From the JOSM `Download data` window (`OpenStreetMap data`, `Raw GPS data`, `Notes`, ..., `MapRoulette Tasks`)
2. From the `MapRoulette Tasks` toggle dialog (`Download Data`) which downloads data in the current viewport

## Logging in
Starting tasks and setting their status needs a MapRoulette login. Log in from `Preferences` > `MapRoulette` >
`Login`: JOSM opens MapRoulette in your browser, and the browser returns to JOSM through remote control (port 8111).
Only MapRoulette servers that accept logins from JOSM offer this (for now the `mr-api.osm.lol` test server).
The login is kept like JOSM's own OSM login: in the JOSM preferences, unless a password manager plugin is installed.
JOSM's trace logging (`--trace`) writes request bodies, including the login's refresh token, to the log.

## Workflow
1. Select a MapRoulette task in the mapview *or* in the `MapRoulette Tasks` window
2. Click on `Start Task` -- this will "lock" the task on MapRoulette until you finish it or click on `Stop Task`.
   This helps ensure that you aren't mapping something concurrently with someone else.
3. Perform the task. Instructions can be viewed in the `Current MapRoulette Task` toggle dialog.
4. In `Current MapRoulette Task`, select the appropriate action (`False Positive`, `Too hard/Cannot see`, `Fixed`,
   `Already Fixed/Not an issue`, or `Skipped`; some options are hidden in drop-down menus).
5. The status is sent to MapRoulette right away, and the task is finished.
6. Upload your edits with `File` > `Upload data`. The changeset is then linked to the tasks you marked `Fixed`.

If a task specifies an OSM primitive, and you have modified a primitive specified
by the task, you will be asked if you have fixed the problem _even if you haven't
locked the task_ when you upload the changed primitive.

# Development
The plugin builds standalone with Maven (JDK 17+); JOSM is fetched from https://josm.openstreetmap.de/repository/.
* `mvn verify` builds `target/MapRoulette.jar` and runs the tests against the latest JOSM.
* `mvn compile -Djosm.compile.version=19528` checks that it still compiles against the minimum JOSM version
  (`plugin.main.version`, which must match `build.xml`).
* `mvn spotless:apply` formats the code with the JOSM formatter (`tools/eclipse/formatter.xml`).

The Ant `build.xml` is kept for building inside the JOSM plugins SVN tree.

# Author
* Taylor Smock <tsmock@protonmail.com>

# License
GPLv3 or any later version