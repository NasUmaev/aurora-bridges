package com.aurora.gtnh;

import java.util.Arrays;
import java.util.List;

import net.minecraft.command.CommandBase;
import net.minecraft.command.ICommandSender;

public final class AuroraCommand extends CommandBase {

    @Override
    public String getCommandName() {
        return "aurora";
    }

    @Override
    public List<String> getCommandAliases() {
        return Arrays.asList("аврора", "av");
    }

    @Override
    public String getCommandUsage(ICommandSender sender) {
        return "/aurora <сообщение> | /aurora setup | /aurora knowledge [reload|update]";
    }

    @Override
    public boolean canCommandSenderUseCommand(ICommandSender sender) {
        return true;
    }

    @Override
    public void processCommand(ICommandSender sender, String[] args) {
        if (args.length == 0) {
            throw new net.minecraft.command.WrongUsageException(getCommandUsage(sender));
        }
        if (args.length == 1 && "setup".equalsIgnoreCase(args[0])) {
            AuroraClient.openSetup();
            return;
        }
        if (args.length == 1 && "knowledge".equalsIgnoreCase(args[0])) {
            List<String> profiles = AuroraClient.knowledgeProfiles();
            String message = profiles.isEmpty() ? "§d[Аврора] §7Внешние профили знаний не загружены."
                : "§d[Аврора] §7Профили знаний: " + profiles;
            sender.addChatMessage(new net.minecraft.util.ChatComponentText(message));
            return;
        }
        if (args.length == 2 && "knowledge".equalsIgnoreCase(args[0]) && "reload".equalsIgnoreCase(args[1])) {
            AuroraClient.reloadKnowledge();
            sender.addChatMessage(new net.minecraft.util.ChatComponentText("§d[Аврора] §7Перезагружаю знания…"));
            return;
        }
        if (args.length == 2 && "knowledge".equalsIgnoreCase(args[0]) && "update".equalsIgnoreCase(args[1])) {
            AuroraClient.updateKnowledge();
            sender.addChatMessage(new net.minecraft.util.ChatComponentText("§d[Аврора] §7Проверяю обновления знаний…"));
            return;
        }
        AuroraClient.sendPrompt(String.join(" ", args));
    }
}
