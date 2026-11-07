package org.atvideo.command;

import com.mojang.brigadier.StringReader;
import com.mojang.brigadier.arguments.ArgumentType;
import com.mojang.brigadier.context.CommandContext;
import com.mojang.brigadier.exceptions.CommandSyntaxException;
import com.mojang.brigadier.suggestion.Suggestions;
import com.mojang.brigadier.suggestion.SuggestionsBuilder;
import net.minecraft.network.chat.Component;

import java.util.Collection;
import java.util.List;
import java.util.concurrent.CompletableFuture;

/**
 * 接受 @s / @p / @a / @r 以及带参数的选择器（@a[team=red] 等）。
 * Brigadier 内置 word() 不接受 @，这是自定义替代。
 */
public final class TargetArgumentType implements ArgumentType<String> {

    private TargetArgumentType() {
    }

    public static TargetArgumentType target() {
        return new TargetArgumentType();
    }

    public static String getTarget(CommandContext<?> ctx, String name) {
        return ctx.getArgument(name, String.class);
    }

    @Override
    public String parse(StringReader reader) throws CommandSyntaxException {
        int start = reader.getCursor();
        if (!reader.canRead() || reader.peek() != '@') {
            throw CommandSyntaxException.BUILT_IN_EXCEPTIONS.readerExpectedSymbol()
                    .createWithContext(reader, "@");
        }
        reader.skip();  // 消费 @

        // 读 @ 后的字符，遇到空格停止（除非在 [] 内）
        int bracketDepth = 0;
        while (reader.canRead()) {
            char c = reader.peek();
            if (c == '[') {
                bracketDepth++;
            } else if (c == ']' && bracketDepth > 0) {
                bracketDepth--;
            }
            if (c == ' ' && bracketDepth == 0) {
                break;
            }
            reader.skip();
        }
        return reader.getString().substring(start, reader.getCursor());
    }

    @Override
    public <S> CompletableFuture<Suggestions> listSuggestions(
            CommandContext<S> context, SuggestionsBuilder builder) {
        builder.suggest("@s", Component.literal("自己"));
        builder.suggest("@p", Component.literal("最近的玩家"));
        builder.suggest("@a", Component.literal("所有在线玩家"));
        builder.suggest("@r", Component.literal("随机玩家"));
        return builder.buildFuture();
    }

    @Override
    public Collection<String> getExamples() {
        return List.of("@s", "@p", "@a", "@r");
    }
}