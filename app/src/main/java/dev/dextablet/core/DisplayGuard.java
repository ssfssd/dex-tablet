package dev.dextablet.core;

import java.util.*;
import java.util.regex.*;

public final class DisplayGuard {
    private DisplayGuard() { }
    public static boolean empty(String value) { return value==null||value.trim().isEmpty()||value.trim().equals("null"); }
    public static boolean mayRestore(String current,String owned) { return owned!=null&&!owned.isEmpty()&&owned.equals(current); }
    public static Set<Integer> ids(String list) {
        Set<Integer> ids=new HashSet<>(); Matcher m=Pattern.compile("--display-id=(\\d+)").matcher(list);
        while(m.find()) ids.add(Integer.parseInt(m.group(1))); return ids;
    }
    public static int uniqueNewDisplay(Set<Integer> before,String list,int width,int height) {
        Matcher m=Pattern.compile("--display-id=(\\d+)\\s+\\((\\d+)x(\\d+)\\)").matcher(list); int found=-1;
        while(m.find()) {
            int id=Integer.parseInt(m.group(1));
            if(id>0&&!before.contains(id)&&Integer.parseInt(m.group(2))==width&&Integer.parseInt(m.group(3))==height) {
                if(found!=-1) return -1; found=id;
            }
        }
        return found;
    }
    public static int virtualId(String log) {
        Matcher m=Pattern.compile("New display:.*?\\(id=(\\d+)\\)").matcher(log);
        return m.find()?Integer.parseInt(m.group(1)):-1;
    }
}
