package hu.letscode.tdd;

import static java.nio.file.StandardWatchEventKinds.ENTRY_CREATE;
import static java.nio.file.StandardWatchEventKinds.ENTRY_DELETE;
import static java.nio.file.StandardWatchEventKinds.ENTRY_MODIFY;
import static java.nio.file.StandardWatchEventKinds.OVERFLOW;

import java.io.File;
import java.io.FileOutputStream;
import java.io.IOException;
import java.net.MalformedURLException;
import java.net.URL;
import java.nio.file.ClosedWatchServiceException;
import java.nio.file.FileSystems;
import java.nio.file.FileVisitResult;
import java.nio.file.FileVisitor;
import java.nio.file.Files;
import java.nio.file.LinkOption;
import java.nio.file.Path;
import java.nio.file.StandardWatchEventKinds;
import java.nio.file.WatchEvent;
import java.nio.file.WatchKey;
import java.nio.file.WatchService;
import java.nio.file.attribute.BasicFileAttributes;
import java.util.ArrayList;
import java.util.Arrays;
import java.util.HashMap;
import java.util.HashSet;
import java.util.List;
import java.util.Map;
import java.util.Map.Entry;
import java.util.Properties;
import java.util.Set;
import java.util.concurrent.TimeUnit;

import org.apache.maven.Maven;
import org.apache.maven.execution.DefaultMavenExecutionRequest;
import org.apache.maven.execution.MavenExecutionRequest;
import org.apache.maven.execution.MavenExecutionResult;
import org.apache.maven.execution.MavenSession;
import org.apache.maven.plugin.AbstractMojo;
import org.apache.maven.plugin.MojoExecutionException;
import org.apache.maven.plugin.MojoFailureException;
import org.apache.maven.plugin.prefix.PluginPrefixResolver;
import org.apache.maven.plugin.version.PluginVersionResolver;
import org.apache.maven.plugins.annotations.Component;
import org.apache.maven.plugins.annotations.Mojo;
import org.apache.maven.plugins.annotations.Parameter;
import org.apache.maven.plugins.annotations.ResolutionScope;
import org.codehaus.plexus.util.DirectoryScanner;

import fr.jcgay.notification.Application;
import fr.jcgay.notification.Icon;
import fr.jcgay.notification.Notification;
import fr.jcgay.notification.Notifier;
import fr.jcgay.notification.SendNotification;

/**
 * Utility for watching directories/files and triggering a maven goal.
 *
 * @author tacsiazuma
 */
@Mojo(name = "run", requiresDependencyResolution = ResolutionScope.COMPILE_PLUS_RUNTIME, threadSafe = true)
public class RunMojo extends AbstractMojo {

    @Parameter(defaultValue = "${session}", required = true, readonly = true)
    protected MavenSession session;
    
    @Parameter(property = "watches", alias = "watcher.watches", required = true)
    protected List<WatchFileSet> watches;

    @Parameter(property = "profiles", alias = "watcher.profiles", required = false)
    protected List<String> profiles;

    @Component
    protected PluginPrefixResolver pluginPrefixResolver;

    @Component
    protected PluginVersionResolver pluginVersionResolver;

    protected Properties executionProperties = new Properties();
    
    @Component
    protected Maven maven;

    private WatchService watchService;
    private Map<Path, WatchFileSet> configMap;
    private Map<Path, WatchKey> pathMap;
    private Map<WatchKey, Path> watchKeyMap;

    @Override
    public void execute() throws MojoExecutionException, MojoFailureException {
    	executionProperties = session.getUserProperties();
    	  
    	Icon successIcon = null;
		successIcon = Icon.create(RunMojo.class.getClassLoader().getResource("success.png"), "letuscodelikegentleman");
		Icon failIcon = null;
		failIcon = Icon.create(RunMojo.class.getClassLoader().getResource("fail.png"), "letuscodelikegentleman");
    	Application app = null;
		app = Application.builder("application/hu.letscode.tdd", "Maven TDD plugin", successIcon ).build();
        Notifier notifier = new SendNotification()
                .setApplication(app)
                .initNotifier();
    	
    	
        this.configMap = new HashMap<>();
        this.pathMap = new HashMap<>();
        this.watchKeyMap = new HashMap<>();

        try {
            watchService = FileSystems.getDefault().newWatchService();
        } catch (Exception e) {
            throw new MojoExecutionException("Unable to create watch service");
        }

        getLog().info("Registering " + watches.size() + " watch sets...");
        
        for (WatchFileSet wfs : watches) {
            registerWatchSet(wfs);
        }

        long longTimeout = 60 * 60 * 24 * 1000L;
        long shortTimeout = 750L;
        long timeout = longTimeout;
        int dueToRunGoal = 0;
        
        while (true) {
            try {
                
                if (timeout > shortTimeout) {
                    getLog().info("Watcher - waiting for changes...");
                }
                
                // timeout to poll for (this way we can let lots of quick changes
                // take place -- and only run the goal when things settles down)
                WatchKey watchKey = watchService.poll(timeout, TimeUnit.MILLISECONDS);
                if (watchKey == null) {
                    // timeout occurred!
                    if (dueToRunGoal > 0) {
                        MavenExecutionRequest request = DefaultMavenExecutionRequest.copy(session.getRequest());
                        
                        if (this.profiles != null && this.profiles.size() > 0) {
                            request.setActiveProfiles(profiles);
                        }
                        request.setUserProperties(executionProperties);
                        request.setGoals(Arrays.asList("compile", "test"));
                        
                        if (!executionProperties.isEmpty()) {
                            MavenExecutionResult executionResult = maven.execute(request);
                            executionProperties = new Properties();
                            if (executionResult.hasExceptions()) {
                                try {
                                	notifier.send(Notification.builder("Tests failed!", "You could do better!", failIcon).build());	
                                } finally {
                                	notifier.close();
                                }
                                
                            } else {
                            	try {
                                	notifier.send(Notification.builder("Tests passed!", "Good job!", successIcon).build());	
                                } finally {
                                	notifier.close();
                                }
                            }
                        }
                    }
                    
                    timeout = longTimeout;
                    dueToRunGoal = 0;
                    continue;
                }
                
                // schedule the goal to run
                timeout = shortTimeout;
                dueToRunGoal++;
                
                Path watchPath = watchKeyMap.get(watchKey);

                List<WatchEvent<?>> pollEvents = watchKey.pollEvents(); // take events, but don't care what they are!
                for (@SuppressWarnings("rawtypes") WatchEvent event : pollEvents) {
                    dueToRunGoal = analyzeEvents(dueToRunGoal, watchPath, event);
                }

                watchKey.reset();
            } catch (InterruptedException | ClosedWatchServiceException e) {
                break;
            }
        }
    }

	private int analyzeEvents(int dueToRunGoal, Path watchPath, WatchEvent event) {
		if (event.context() instanceof Path) {
		    // event is always relative to what was watched (e.g. testdir)
		    Path eventPath = (Path) event.context();
		    // resolve relative to path watched (e.g. dir/watched/testdir)
		    Path path = watchPath.resolve(eventPath);
		    
		    File file = path.toFile();
		    String fileOrDir = (file.isDirectory() ? "directory" : "file");
		    
		    // find the assigned watch config so we can see if has includes/excludes
		    WatchFileSet wfs = findWatchFileSet(path);

		    getLog().debug("eventPath: " + eventPath);
		    getLog().debug("watchFileSet: " + wfs);
		    
		    boolean matches = matches(eventPath.toString(), wfs);
		    getLog().debug("Watcher - matches=" + matches);
		    setTestClass(path);
		    if (event.kind() == StandardWatchEventKinds.ENTRY_CREATE) {
		        matches = createEvent(path, file, fileOrDir, wfs, matches);
		    } else if (event.kind() == StandardWatchEventKinds.ENTRY_DELETE) {
		        matches = deleteEvent(event, path, fileOrDir, matches);
		    } else if (event.kind() == StandardWatchEventKinds.ENTRY_MODIFY) {
		        matches = modifyEvent(path, file, fileOrDir, matches);
		    } else if (event.kind() == StandardWatchEventKinds.OVERFLOW) {
		        overflowEvent();
		    }
		    
		    // if no match then do NOT trigger a change
		    if (!matches) {
		        getLog().info("Change either a dir or did not match includes/excludes (not triggering goals...)");
		        dueToRunGoal--;
		    }
		}
		return dueToRunGoal;
	}

	private boolean createEvent(Path path, File file, String fileOrDir, WatchFileSet wfs, boolean matches) {
		getLog().info("Watcher - " + fileOrDir + " created: " + path);
		// only schedule new directory to be watched if we're recursive
		if (file.isDirectory()) {
		    if (wfs.isRecursive()) {
		        // register this new directory as something to watch
		        walkTreeAndSetWatches(file, new File(wfs.getDirectory()));
		    }
		    // directories by themselves do not trigger a match
		    matches = false;
		}
		return matches;
	}

	private boolean deleteEvent(WatchEvent event, Path path, String fileOrDir, boolean matches) {
		getLog().info("Watcher - " + fileOrDir + " deleted: " + path);
		// need to unregister any stale directories from watching
		int count = unregisterStaleWatches();
		if (count > 0 && count == event.count()) {
		    // if stale dirs were removed and it matches events count
		    // then should be safe to ignore it
		    matches = false;
		}
		return matches;
	}

	private boolean modifyEvent(Path path, File file, String fileOrDir, boolean matches) {
		getLog().info("Watcher - " + fileOrDir + " modified: " + path);
		// only schedule new directory to be watched if we're recursive
		if (file.isDirectory()) {
		    // directories by themselves do not trigger a match
		    matches = false;
		}
		return matches;
	}

	private void overflowEvent() {
		getLog().warn("Watcher - some events may have been discarded!!!!");
		getLog().warn("Ideally, just restart maven to pick it up again");
	}

	private void registerWatchSet(WatchFileSet wfs) throws MojoFailureException {
		getLog().info("Registering watch set: " + wfs);
		
		File dir = new File(wfs.getDirectory());
		if (!dir.exists()) {
		    throw new MojoFailureException("Directory " + dir + " does not exist. Unable to watch a dir that does not exist");
		}
		if (!dir.isDirectory()) {
		    throw new MojoFailureException("Unable to watch " + dir + " - its not a directory");
		}
		
		// add config for this path
		// maven is somehow garbage collecting my includes value -- create copy instead...
		this.configMap.put(dir.toPath(), wfs);
		
		if (wfs.isRecursive()) {
		    this.walkTreeAndSetWatches(dir, null);
		} else {
		    this.registerWatch(dir.toPath());
		}
	}
    
    private void setTestClass(Path file) {
    	getLog().debug("Checking if is a test class: " + file.getFileName().toString());
    	if (file.getFileName().toString().matches("(.*)Test(.*)")) {
    		executionProperties.setProperty("test", file.getFileName().toString().replaceAll(".java", ""));	
    	}
    }
    
    public void addWatch(WatchFileSet wfs) {
        if (this.watches == null) {
            this.watches = new ArrayList<>();
        }
        this.watches.add(wfs);
    }
    
    private WatchFileSet findWatchFileSet(Path path) {
        // start from back and work to front
        Path p = path;
        while (p != null) {
            if (this.configMap.containsKey(p)) {
                return this.configMap.get(p);
            }
            p = p.getParent();
        }
        return null;
    }
    
    private boolean matches(String name, WatchFileSet wfs) {
        boolean matches = false;
        
        // if no excludes & no includes then everything matches
        if ((wfs.getIncludes() == null || wfs.getIncludes().isEmpty()) &&
                (wfs.getExcludes() == null || wfs.getExcludes().isEmpty())) {
            matches = true;
        }
        
        // process includes first
        if (wfs.getIncludes() != null && !wfs.getIncludes().isEmpty()) {
            for (String include : wfs.getIncludes()) {
                getLog().debug("Trying to match: include=" + include + " for name " + name);
                if (DirectoryScanner.match(include, name)) {
                    matches = true;
                    break;
                }
            }
        }
        else {
            // no specific includes, everything will be included then
            matches = true;
        }
        
        // process excludes second
        if (wfs.getExcludes() != null) {
            for (String exclude : wfs.getExcludes()) {
                getLog().debug("Trying to match: exclude=" + exclude + " for name " + name);
                if (DirectoryScanner.match(exclude, name)) {
                    matches = false;
                    break;
                }
            }
        }
        
        return matches;
    }

    private void walkTreeAndSetWatches(File dir, File root) {
        try {
            // does the new directory have a root we need to check back towards?
            if (root != null) {
                Path parent = dir.toPath().getParent();
                if (!pathMap.containsKey(parent)) {
                    // safer to just re-walk entire tree
                    walkTreeAndSetWatches(root, null);
                    return;
                }
                // otherwise the new directory already has its parent registered!
            }
            
            Files.walkFileTree(dir.toPath(), new FileVisitor<Path>() {
                @Override
                public FileVisitResult preVisitDirectory(Path dir, BasicFileAttributes attrs) throws IOException {
                    registerWatch(dir);
                    return FileVisitResult.CONTINUE;
                }

                @Override
                public FileVisitResult visitFile(Path file, BasicFileAttributes attrs) throws IOException {
                    return FileVisitResult.CONTINUE;
                }

                @Override
                public FileVisitResult visitFileFailed(Path file, IOException exc) throws IOException {
                    return FileVisitResult.CONTINUE;
                }

                @Override
                public FileVisitResult postVisitDirectory(Path dir, IOException exc) throws IOException {
                    return FileVisitResult.CONTINUE;
                }
            });
        } catch (IOException e) {
            // Don't care
        }
    }

    private int unregisterStaleWatches() {
        Set<Path> paths = new HashSet<>(pathMap.keySet());
        Set<Path> stalePaths = new HashSet<>();

        for (Path path : paths) {
            if (!Files.exists(path, LinkOption.NOFOLLOW_LINKS)) {
                stalePaths.add(path);
            }
        }

        if (stalePaths.size() > 0) {
            //logger.log(Level.INFO, "Cancelling stale path watches ...");
            for (Path stalePath : stalePaths) {
                unregisterWatch(stalePath);
            }
        }
        
        return stalePaths.size();
    }

    private void registerWatch(Path dir) {
        if (!pathMap.containsKey(dir)) {
            getLog().info("Watcher - registering watch on dir: " + dir);
            try {
                WatchKey watchKey = dir.register(watchService, ENTRY_CREATE, ENTRY_DELETE, ENTRY_MODIFY, OVERFLOW);
                // reverse maps...
                pathMap.put(dir, watchKey);
                watchKeyMap.put(watchKey, dir);
            } catch (IOException e) {
                // don't care!
            }
        }
    }

    private void unregisterWatch(Path dir) {
        WatchKey watchKey = pathMap.get(dir);
        if (watchKey != null) {
            getLog().info("Watcher - unregistering watch on dir: " + dir);
            watchKey.cancel();
            pathMap.remove(dir);
            watchKeyMap.remove(watchKey);
        }
    }
}
