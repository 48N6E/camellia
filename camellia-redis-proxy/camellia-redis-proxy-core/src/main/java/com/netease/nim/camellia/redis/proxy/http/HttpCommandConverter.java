package com.netease.nim.camellia.redis.proxy.http;

import com.netease.nim.camellia.redis.proxy.command.Command;
import com.netease.nim.camellia.redis.proxy.util.Utils;

import java.util.ArrayList;
import java.util.List;

/**
 * Created by caojiajun on 2024/1/15
 */
public class HttpCommandConverter {

    public static final char singleQuotation = '\'';
    public static final char doubleQuotation = '\"';
    public static final char blank = ' ';

    public static List<Command> convert(HttpCommandRequest request) {
        List<String> commands = request.getCommands();
        List<Command> list = new ArrayList<>(commands.size());
        for (String command : commands) {
            list.add(convert(command));
        }
        return list;
    }

    public static Command convert(String command) {
        if (!command.contains("'") && !command.contains("\"")) {
            String[] split = command.split(" ");
            byte[][] args = new byte[split.length][];
            for (int i=0; i<split.length; i++) {
                args[i] = Utils.stringToBytes(split[i]);
            }
            return new Command(args);
        } else {
            List<String> args = new ArrayList<>();
            boolean inArg = false;
            StringBuilder arg = new StringBuilder();
            Character quotation = null;
            int index = 0;
            char[] array = command.toCharArray();
            for (char c : array) {
                if (c == singleQuotation || c == doubleQuotation) {
                    if (inArg) {
                        if (quotation != null && quotation == c) {
                            boolean lastChar = index == (array.length - 1);
                            if (!lastChar) {
                                boolean nextCharBlack = array[index + 1] == blank;
                                if (!nextCharBlack) {
                                    throw new IllegalArgumentException("Invalid argument(s)");
                                }
                            }
                            quotation = null;
                            inArg = false;
                            args.add(arg.toString());
                            arg = new StringBuilder();
                        } else {
                            arg.append(c);
                        }
                    } else {
                        quotation = c;
                        inArg = true;
                    }
                } else if (blank == c) {
                    if (inArg) {
                        if (quotation != null) {
                            arg.append(c);
                        } else {
                            inArg = false;
                            args.add(arg.toString());
                            arg = new StringBuilder();
                        }
                    }
                } else {
                    arg.append(c);
                    inArg = true;
                }
                index ++;
            }
            if (quotation != null) {
                throw new IllegalArgumentException("Invalid argument(s)");
            }
            if (arg.length() > 0) {
                args.add(arg.toString());
            }
            byte[][] bytes = new byte[args.size()][];
            for (int i=0; i<args.size(); i++) {
                bytes[i] = Utils.stringToBytes(args.get(i));
            }
            return new Command(bytes);
        }
    }
}