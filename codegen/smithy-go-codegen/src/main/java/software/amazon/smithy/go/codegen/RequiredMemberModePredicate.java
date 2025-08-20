package software.amazon.smithy.go.codegen;

import software.amazon.smithy.go.codegen.knowledge.GoPointableIndex;
import software.amazon.smithy.go.codegen.knowledge.GoUsageIndex;
import software.amazon.smithy.model.Model;
import software.amazon.smithy.model.shapes.MemberShape;
import software.amazon.smithy.model.traits.RequiredTrait;

public class RequiredMemberModePredicate {
    private final GoSettings.RequiredMemberMode requiredMemberMode;
    private final GoUsageIndex usageIndex;
    private final GoPointableIndex goPointableIndex;
    private final Model model;

    public RequiredMemberModePredicate(Model model, GoSettings.RequiredMemberMode requiredMemberMode) {
        this.model = model;
        this.requiredMemberMode = requiredMemberMode;
        this.usageIndex = GoUsageIndex.of(model);
        this.goPointableIndex = GoPointableIndex.of(model);
    }

    /// If member is required and strict option is enabled it generates value types rather than pointer types (default).
    /// Notbaly, this only applies to output types (types that are reachable from any operation output).
    ///
    /// This does not apply to input types because of client-side verification.
    /// It is impossible to check if value type was set because it always has a default value.
    public boolean isRequiredOutputMember(MemberShape member) {
        final var shape = member.getContainer();
        return isStrictModeEnabled() &&
                member.getMemberTrait(model, RequiredTrait.class).isPresent() &&
                usageIndex.isUsedForOutput(shape) && !usageIndex.isUsedForInput(shape);
    }

    /// Needed because deserializeDocument functions take **T, rather than *T hence we need to deref twice.
    public boolean shouldDerefTwiceForDeserialization(MemberShape member) {
        return isRequiredOutputMember(member) && !isInherentlyValueType(member);
    }

    private boolean isStrictModeEnabled() {
        return requiredMemberMode == GoSettings.RequiredMemberMode.STRICT;
    }

    private boolean isInherentlyValueType(MemberShape member) {
        final var targetShape = model.expectShape(member.getTarget()).getType();
        return goPointableIndex.isInherentlyValue(targetShape);
    }
}
