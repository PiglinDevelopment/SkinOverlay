package dev.piglin.skinoverlay.utils.commands;

import com.mojang.brigadier.arguments.StringArgumentType;
import com.mojang.brigadier.context.CommandContext;
import com.mojang.brigadier.exceptions.CommandSyntaxException;
import com.mojang.brigadier.suggestion.SuggestionProvider;
import com.mojang.brigadier.suggestion.Suggestions;
import com.mojang.brigadier.suggestion.SuggestionsBuilder;
import net.minecraft.commands.CommandSourceStack;
import net.minecraft.network.chat.Component;
import org.jetbrains.annotations.Nullable;

import java.util.Collection;
import java.util.concurrent.CompletableFuture;
import java.util.function.Function;

public record SimpleSuggestionProvider(
        String argumentName,
        Function<CommandContext<CommandSourceStack>, Collection<Suggestion>> function) implements SuggestionProvider<CommandSourceStack> {

    public static SimpleSuggestionProvider noTooltip(String argumentName, Function<CommandContext<CommandSourceStack>, Collection<String>> function) {
        return new SimpleSuggestionProvider(argumentName, function.andThen(c -> c.stream().map(Suggestion::new).toList()));
    }

    @Override
    public CompletableFuture<Suggestions> getSuggestions(CommandContext<CommandSourceStack> context, SuggestionsBuilder builder) throws CommandSyntaxException {
        String arg = "";
        try {
            arg = StringArgumentType.getString(context, argumentName);
        } catch (IllegalArgumentException ignored) {
        }
        String finalArg = arg;
        function.apply(context)
                .stream()
                .filter(s -> s.suggestion().startsWith(finalArg))
                .forEach(s -> builder.suggest(s.suggestion(), s.tooltip() == null ? null : s.tooltip()));
        return builder.buildFuture();
    }

    public record Suggestion(String suggestion, @Nullable Component tooltip) {
        public Suggestion(String suggestion) {
            this(suggestion, null);
        }
    }
}
