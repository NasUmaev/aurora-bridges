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
        return "/aurora <сообщение> | /aurora setup | /aurora memory";
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
        if (args.length == 1 && "memory".equalsIgnoreCase(args[0])) {
            List<String> memories = AuroraClient.recentMemories();
            if (memories.isEmpty()) {
                sender.addChatMessage(new net.minecraft.util.ChatComponentText("§d[Аврора] §7Память пока пуста."));
            } else {
                sender.addChatMessage(new net.minecraft.util.ChatComponentText("§d[Аврора] §7Последние события:"));
                for (String memory : memories) {
                    sender.addChatMessage(new net.minecraft.util.ChatComponentText("§8• §7" + memory));
                }
            }
            return;
        }
        AuroraClient.sendPrompt(String.join(" ", args));
    }
}
