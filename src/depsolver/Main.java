package depsolver;

import com.alibaba.fastjson.JSON;
import com.alibaba.fastjson.TypeReference;
import java.io.BufferedReader;
import java.io.FileReader;
import java.io.IOException;
import java.util.ArrayList;
import java.util.HashSet;
import java.util.List;
import java.util.Set;
import java.util.Map;
import java.util.HashMap;
import java.util.concurrent.atomic.AtomicInteger;

class Package {
  private String name;
  private String version;
  private Integer size;
  private List<List<String>> depends = new ArrayList<>();
  private List<String> conflicts = new ArrayList<>();

  public String getName() { return name; }
  public String getVersion() { return version; }
  public Integer getSize() { return size; }
  public List<List<String>> getDepends() { return depends; }
  public List<String> getConflicts() { return conflicts; }
  public void setName(String name) { this.name = name; }
  public void setVersion(String version) { this.version = version; }
  public void setSize(Integer size) { this.size = size; }
  public void setDepends(List<List<String>> depends) { this.depends = depends; }
  public void setConflicts(List<String> conflicts) { this.conflicts = conflicts; }
}

public class Main {

  private static int UNINSTALL_COST = 1000000;
  private static double MAX_RUN_TIME = 45000;
  private double startTime;
  private boolean forceFinish;
  private List<Package> repo;
  private List<String> initial;
  private List<String> constraints;
  private HashSet<List<Package>> seen;
  private List<String> finalCommands;
  public Integer finalCost;

  public Main(List<Package> repo, List<String> initial, List<String> constraints) {
    this.repo = repo;
    this.initial = initial;
    this.constraints = constraints;
    seen = new HashSet<>();
    finalCommands = new ArrayList<>();
    finalCost = 0;
    startTime = System.currentTimeMillis();
    forceFinish = false;
  }

  public static void main(String[] args) throws IOException {

    TypeReference<List<Package>> repoType = new TypeReference<List<Package>>() {};
    List<Package> repo = JSON.parseObject(readFile(args[0]), repoType);
    TypeReference<List<String>> strListType = new TypeReference<List<String>>() {};
    List<String> initial = JSON.parseObject(readFile(args[1]), strListType);
    List<String> constraints = JSON.parseObject(readFile(args[2]), strListType);

    Main main = new Main(repo, initial, constraints);
    List<Package> initialOut = main.getInitialPackages();
    main.search(new ArrayList<>(), initialOut);

    System.out.println(main.constructOutput());
  }

  private List<Package> getInitialPackages() {
    List<Package> state = new ArrayList<>();
    for (String req : initial) {
      String[] splReq = req.split("=");
      Package p = repo.stream().filter(x -> { // this may need size optimisation if version is not specified?
        return splReq[0].equals(x.getName())
            && (splReq.length == 1 || splReq[1].equals(x.getVersion()));
      }).max((x1, x2) -> x1.getSize() < x2.getSize() ? -1 : 1).get();
      state.add(p);
    }
    return state;
  }

  private void search(List<String> commands, List<Package> current) {
    if (forceFinish || hasSeen(current) || !isValid(current)) return;
    makeSeen(current);
    if (isFinal(current)) {
      finalCommands = finalCommands.isEmpty() ? commands : getMinCommandSequence(commands, finalCommands);
      if (hasExceededTimeConstraint()) {
        forceFinish = true;
      }
      return;
    }

    for (Package p : repo) {
      boolean isInstalled = current.contains(p);
      String term = getPackageKey(p);

      if (isInstalled) {
        if (!commands.contains("+" + term) || commands.size() < constraints.size() && requiresUninstall(p)) {
          List<Package> nextState = uninstallPackage(p, current);
          List<String> nextCommands = getNextCommands(commands, current, p);
          search(nextCommands, nextState);
        }
      } else {
        if (!commands.contains("-" + term)) {
          List<Package> nextState = installPackage(p, current);
          List<String> nextCommands = getNextCommands(commands, current, p);
          search(nextCommands, nextState);
        }
      }
    }
    seen.remove(current);
  }

  boolean hasSeen(List<Package> repo) {
    return seen.contains(repo);
  }

  private void makeSeen(List<Package> repo) {
    seen.add(repo);
  }

  private String getNextAction(Package p, List<Package> currentState) {
    return (currentState.contains(p) ? "-" : "+") + getPackageKey(p);
  }

  private List<String> getNextCommands(List<String> commands, List<Package> packages, Package p) {
    String action = getNextAction(p, packages);
    List<String> nextCommands = new ArrayList<>(commands);
    nextCommands.add(action);
    return nextCommands;
  }

  private List<Package> installPackage(Package p, List<Package> packages) {
    List<Package> copy = new ArrayList<>(packages);
    copy.add(p);
    return copy;
  }

  private List<Package> uninstallPackage(Package p, List<Package> packages) {
    List<Package> copy = new ArrayList<>(packages);
    copy.remove(p);
    return copy;
  }

  private boolean requiresUninstall(Package p) {
    String name = p.getName();
    String nameVer = getPackageKey(p);

    if (constraints.contains('+' + name) || constraints.contains('+' + nameVer)) return false;
    return constraints.contains('-' + name) || constraints.contains('-' + nameVer);
  }

  private List<String> getMinCommandSequence(List<String> a, List<String> b) {
    int loopSize = Math.max(a.size(), b.size());
    final AtomicInteger sumA = new AtomicInteger(0);
    final AtomicInteger sumB = new AtomicInteger(0);

    for (int i = 0; i < loopSize; i++) {
      String strA = i < a.size() ? a.get(i) : null;
      String strB = i < b.size() ? b.get(i) : null;
      boolean evalA = strA != null;
      boolean evalB = strB != null;
      final String[] aProps = evalA ? getNameAndVersion(strA) : null;
      final boolean installA = evalA && aProps[0].charAt(0) != '-';
      final String[] bProps = evalB ? getNameAndVersion(strB) : null;
      final boolean installB = evalB && bProps[0].charAt(0) != '-';

      repo.forEach(p -> {
        String name = p.getName();
        String version = p.getVersion();
        Integer size = p.getSize();

        if (evalA && name.equals(aProps[0].substring(1)) && version.equals(aProps[1])) {
          int cost = installA ? size : UNINSTALL_COST;
          sumA.addAndGet(cost);
        }
        if (evalB && name.equals(bProps[0].substring(1)) && version.equals(bProps[1])) {
          int cost = installB ? size : UNINSTALL_COST;
          sumB.addAndGet(cost);
        }
      });
    }
    boolean aIsSmaller = sumA.get() < sumB.get();
    finalCost = aIsSmaller ? sumA.get() : sumB.get();
    return aIsSmaller ? a : b;
  }

  static boolean isValid(List<Package> repo) {
    Set<String> trackedNames = new HashSet<>();
    HashMap<String, Package> trackedPackages = new HashMap<>();

    if (!hasValidDependencies(repo)) {
      return false;
    }

    for (Package p : repo) {
      if (hasConflicts(trackedNames, trackedPackages, p)) return false;
      String key = getPackageKey(p);
      trackedNames.add(key);
      trackedPackages.put(key, p);
    }
    return true;
  }

  static boolean hasConflicts(Set<String> trackedNames, Map<String, Package> trackedPackages, Package p) {
    for (Package sp : trackedPackages.values()) {
      List<String> conflicts = sp.getConflicts();
      for (String conflict : conflicts) {
        String op = getOperator(conflict);
        if (op.equals("=") && trackedNames.contains(getPackageKey(p))) {
          return true;
        }

        String[] constraints = splitRequirement(op, conflict);
        if (constraints[0].equals(p.getName())) {
          if (satisfiesDependency(p, conflict)) {
            return true;
          }
        }
      }
    }
    for (String conflict : p.getConflicts()) {
      String op = getOperator(conflict);

      if (op.equals("=") && trackedNames.contains(conflict)) {
        return true;
      }

      String[] constraints = splitRequirement(op, conflict);

      for (String name : trackedNames) {
        if (name.contains(constraints[0])) {
          if (constraints.length > 1) {
            Package seenEquivalent = trackedPackages.get(name);
            if (seenEquivalent != null && satisfiesDependency(seenEquivalent, conflict)) {
              return true;
            }
          } else {
            if (name.contains(constraints[0])) {
              return true;
            }
          }
        }
      }
    }
    return false;
  }

  static boolean hasValidDependencies(List<Package> repo) {
    if (repo.isEmpty()) return true;

    Package p = repo.get(repo.size() - 1);

    List<List<String>> deps = p.getDepends();
    for (int i = 0; i < deps.size(); i++) {
      boolean meetsDisj = false;
      for (String disj : deps.get(i)) {
        String op = getOperator(disj);
        String[] constraints = splitRequirement(op, disj);
        String depName = constraints[0];
        for (int j = 0; j < repo.size() - 1; j++) {
          Package x = repo.get(j);
          String name = x.getName();
          if (name.contains(depName)) {
            if (constraints.length == 1) {
              meetsDisj = true;
              break;
            } else {
              if (satisfiesDependency(x, disj)) {
                meetsDisj = true;
                break;
              }
            }
          }
        }
      }
      if (!meetsDisj) {
        return false;
      }
    }
    return true;
  }

  static boolean satisfiesDependency(Package seenPackage, String constraint) {
    String seenVerStr = seenPackage.getVersion();
    String op = getOperator(constraint);
    String[] constraintsObj = splitRequirement(op, constraint);

    if (constraintsObj.length == 1) return true;

    List<Integer> seenVer = getNumericVersion(seenVerStr);
    List<Integer> constraintVer = getNumericVersion(constraintsObj[1]);

    int comp = compareVersion(seenVer, constraintVer);

    return (comp == 1 && (op.equals(">") || op.equals(">=")))
        || (comp == 0 && (op.equals("=") || op.equals(">=") || op.equals("<=")))
        || (comp == -1 && (op.equals("<") || op.equals("<=")));
  }

  /**
   *
   * @param l1
   * @param l2
   * @return req > given (1), req == given (0), req < given (-1)
   */
  static int compareVersion(List<Integer> l1, List<Integer> l2) {
    int maxLen = Math.max(l1.size(), l2.size());
    for (int i = 0; i < maxLen; i++) {
      int v1;
      int v2;

      if (i < l1.size()) v1 = l1.get(i);
      else return -1;
      if (i < l2.size()) v2 = l2.get(i);
      else return 1;

      if (v1 > v2) return 1;
      else if (v1 < v2) return -1;
    }
    return 0;
  }

  static String[] getNameAndVersion(String nameVer) {
    return splitRequirement("=", nameVer);

  }

  static String[] splitRequirement(String op, String fullVerString) {
    if (op.equals("")) return new String[]{ fullVerString };
    else return fullVerString.split(op); // {"A", "1.3"}
  }

  static String[] splitConstraint(String constraint) {

    String op = String.valueOf(constraint.charAt(0));
    String nameAndVersion = constraint.substring(1);

    String[] split = nameAndVersion.split("=");
    if (split.length == 1) {
      return new String[]{op, split[0]};
    }
    return new String[]{op, split[0], split[1]};
  }

  static String getOperator(String v) {
    if (v.contains(">=")) {
      return ">=";
    } else if (v.contains("<=")) {
      return "<=";
    } else if (v.contains(">")) {
      return ">";
    } else if (v.contains("<")) {
      return "<";
    } else if (v.contains("=")) {
      return "=";
    }
    return "";
  }

  static String getPackageKey(Package p) {
    return p.getName() + "=" + p.getVersion();
  }

  static List<Integer> getNumericVersion(String verString) {
    List<Integer> numericVersion = new ArrayList<>();
    String[] spl = verString.split("\\.");
    for (String s : spl) {
      numericVersion.add(Integer.parseInt(s));
    }
    return numericVersion;
  }

  private boolean isFinal(List<Package> repo) {
    for (String c : constraints) {
      String[] cSpl = splitConstraint(c);
      if (cSpl[0].equals("+")) {
        boolean foundMatch = false;
        for (Package p : repo) {
          if (p.getName().equals(cSpl[1])
              && (cSpl.length == 2 || p.getVersion().equals(cSpl[2]))) {
            foundMatch = true;
            break;
          }
        }
        if (!foundMatch) {
          return false;
        }
      } else {
        for (Package p : repo) {
          if (p.getName().equals(cSpl[1])
              && (cSpl.length == 2 || p.getVersion().equals(cSpl[2]))) {
            return false;
          }
        }
      }
    }
    return true;
  }

  private boolean hasExceededTimeConstraint() {
    return startTime + MAX_RUN_TIME < System.currentTimeMillis();
  }

  private String constructOutput() {
    if (finalCommands.size() == 0) {
      return "[]";
    }
    StringBuilder output = new StringBuilder();
    output.append("[\n");
    if (finalCommands.size() > 1) {
      for (int i = 0; i < finalCommands.size() - 1; i++) {
        output.append("  \"").append(finalCommands.get(i)).append("\",\n");
      }
      output.append("  \"").append(finalCommands.get(finalCommands.size() - 1)).append("\"\n");
    } else {
      output.append("  \"").append(finalCommands.get(0)).append("\"\n");
    }
    output.append("]");
    return output.toString();
  }

  static String readFile(String filename) throws IOException {
    BufferedReader br = new BufferedReader(new FileReader(filename));
    StringBuilder sb = new StringBuilder();
    br.lines().forEach(line -> sb.append(line));
    return sb.toString();
  }
}
