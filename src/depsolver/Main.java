package depsolver;

import com.alibaba.fastjson.JSON;
import com.alibaba.fastjson.TypeReference;
import java.io.BufferedReader;
import java.io.FileReader;
import java.io.IOException;
import java.util.*;

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

  private List<Package> repo;
  private List<String> initial;
  private List<String> constraints;
  private HashSet<List<Package>> seen;
  private List<Package> finalState;
  private String finalCommands;

  public Main(List<Package> repo, List<String> initial, List<String> constraints) {
    this.repo = repo;
    this.initial = initial;
    this.constraints = constraints;
    seen = new HashSet<>();
    finalCommands = null;
  }

  public static String[] getArgs() {
    String testDir = "tests/example-0/";
    String repo = testDir + "repository.json";
    String init = testDir + "initial.json";
    String constraints = testDir + "constraints.json";
    return new String[]{ repo, init, constraints };
  }

  public static void main(String[] args) throws IOException {
    // Custom args
    args = getArgs();

    TypeReference<List<Package>> repoType = new TypeReference<List<Package>>() {};
    List<Package> repo = JSON.parseObject(readFile(args[0]), repoType);
    TypeReference<List<String>> strListType = new TypeReference<List<String>>() {};
    List<String> initial = JSON.parseObject(readFile(args[1]), strListType);
    List<String> constraints = JSON.parseObject(readFile(args[2]), strListType);

    System.out.println("initial:");
    initial.forEach(System.out::println);
    System.out.println("constraints:");
    constraints.forEach(System.out::println);
    System.out.println("code:");

    Main main = new Main(repo, initial, constraints);
    List<Package> initialOut = main.getInitialPackages();
    main.search("", initialOut);

    main.finalState.forEach(x -> System.out.println(x.getName()));

    // CHANGE CODE BELOW:
    // using repo, initial and constraints, compute a solution and print the answer
//    for (Package p : repo) {
//      System.out.printf("package %s version %s\n", p.getName(), p.getVersion());
//      for (List<String> clause : p.getDepends()) {
//        System.out.printf("  dep:");
//        for (String q : clause) {
//          System.out.printf(" %s", q);
//        }
//        System.out.printf("\n");
//      }
//    }
  }

  private List<Package> getInitialPackages() {
    List<Package> state = new ArrayList<>();
    for (String name : initial) {
      Package p = repo.stream().filter(x -> name.equals(x.getName())).findFirst().get();
      state.add(p);
    }
    return state;
  }


  private void search(String commands, List<Package> current) {
    if (!isValid(current)) return;
    if (hasSeen(current)) return;
    makeSeen(current);
    if (isFinal(current)) {
      // solution found
      // save global command -- eval if its shorter than current. DO NOT END PROCESS
      if (finalCommands == null || commands.length() < finalCommands.length()) {
        finalState = current;
        finalCommands = commands;
      }
      return;
    }
    for (Package p : repo) {
      String action = "";
      List<Package> nextState = current;
      if (!commands.contains("+" + p.getName())) {
        action = getNextAction(p, current);
        nextState = changeState(p, current);
      }
      search(commands + action, nextState);
    }
  }

  private String getNextAction(Package p, List<Package> currentState) {
    return (currentState.contains(p) ? "-" : "+") + p.getName();
  }

  private List<Package> changeState(Package p, List<Package> old) {
    List<Package> repo = new ArrayList<>(old);
    if (repo.contains(p)) {
      repo.remove(p);
    } else {
      repo.add(p);
    }
    return repo;
  }

  private void makeSeen(List<Package> repo) {
    seen.add(repo);
  }

  // implement checks for lexicographical order of packages -- will greatly reduce outcomes
  static boolean isValid(List<Package> repo) {
    Set<String> trackedNames = new HashSet<>();
    HashMap<String, Package> trackedPackages = new HashMap<>();
    for (Package p : repo) {
      for (Package sp : trackedPackages.values()) {
        List<String> conflicts = sp.getConflicts();
        for (String conflict : conflicts) {
          String op = getOperator(conflict);
          String[] constraints = splitRequirement(op, conflict);
          if (constraints[0].equals(p.getName())) {
            if (satisfiesDependency(p, conflict)) {
              return false;
            }
          }
        }
      }
      for (List<String> conj : p.getDepends()) {
        boolean meetsDisj = false;
        for (String disj : conj) {
          String op = getOperator(disj);
          String[] constraints = splitRequirement(op, disj);
          String depName = constraints[0];
          if (trackedNames.contains(depName)) {
            if (constraints.length > 1) {
              Package seenEquivalent = trackedPackages.get(depName);
              if (satisfiesDependency(seenEquivalent, disj)) {
                meetsDisj = true;
                break;
              }
            } else {
              meetsDisj = true;
              break;
            }
          }
        }
        if (!meetsDisj) {
          return false;
        }
      }
      for (String conflict : p.getConflicts()) {
        String op = getOperator(conflict);
        String[] constraints = splitRequirement(op, conflict);
        String depName = constraints[0];
        if (trackedNames.contains(depName)) {
          if (constraints.length > 1) {
            Package seenEquivalent = trackedPackages.get(depName);
            if (satisfiesDependency(seenEquivalent, conflict)) {
              return false;
            }
          } else {
            return false;
          }
        }
      }
      trackedNames.add(p.getName());
      trackedPackages.put(p.getName(), p);
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

  static String[] splitRequirement(String op, String fullVerString) {
    if (op.equals("")) return new String[]{ fullVerString };
    else return fullVerString.split(op); // {"A", "1.3"}
  }

  static String[] splitConstraint(String constraint) {
    String[] firstSplit = constraint.split("=");
    String[] secondSplit = firstSplit[0].split("");
    // + or -, name, version
    if (firstSplit.length == 1) {
      return new String[]{secondSplit[0], secondSplit[1]};
    } else {
      return new String[]{secondSplit[0], secondSplit[1], firstSplit[1]};
    }
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
          }
        }
        if (!foundMatch) {
          return false;
        }
      } else {
        for (Package p : repo) {
          if (p.getName().equals(cSpl[0])
              && (cSpl.length == 1 || p.getVersion().equals(cSpl[1]))) {
            return true;
          }
        }
      }
    }
    return true;
  }

  boolean hasSeen(List<Package> repo) {
    return seen.contains(repo);
  }

  static String readFile(String filename) throws IOException {
    BufferedReader br = new BufferedReader(new FileReader(filename));
    StringBuilder sb = new StringBuilder();
    br.lines().forEach(line -> sb.append(line));
    return sb.toString();
  }
}
