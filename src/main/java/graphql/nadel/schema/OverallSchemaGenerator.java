package graphql.nadel.schema;

import graphql.Assert;
import graphql.GraphQLError;
import graphql.GraphQLException;
import graphql.Internal;
import graphql.language.DirectiveDefinition;
import graphql.language.FieldDefinition;
import graphql.language.InputObjectTypeDefinition;
import graphql.language.NamedNode;
import graphql.language.ObjectTypeDefinition;
import graphql.language.SDLDefinition;
import graphql.language.SchemaDefinition;
import graphql.language.SourceLocation;
import graphql.nadel.DefinitionRegistry;
import graphql.nadel.Operation;
import graphql.schema.GraphQLSchema;
import graphql.schema.idl.RuntimeWiring;
import graphql.schema.idl.SchemaGenerator;
import graphql.schema.idl.TypeDefinitionRegistry;
import graphql.schema.idl.WiringFactory;

import java.util.ArrayList;
import java.util.Arrays;
import java.util.HashSet;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Optional;
import java.util.Set;

import static graphql.language.ObjectTypeDefinition.newObjectTypeDefinition;

@Internal
public class OverallSchemaGenerator {

    public GraphQLSchema buildOverallSchema(List<DefinitionRegistry> serviceRegistries, DefinitionRegistry commonTypes, WiringFactory wiringFactory) {
        SchemaGenerator schemaGenerator = new SchemaGenerator();
        RuntimeWiring runtimeWiring = RuntimeWiring.newRuntimeWiring()
                .wiringFactory(wiringFactory)
                .build();
        return schemaGenerator.makeExecutableSchema(createTypeRegistry(serviceRegistries, commonTypes), runtimeWiring);
    }

    private TypeDefinitionRegistry createTypeRegistry(List<DefinitionRegistry> serviceRegistries, DefinitionRegistry commonTypes) {
        //TODO: this merging not completely correct for example schema definition nodes are not handled correctly
        Map<Operation, List<FieldDefinition>> fieldsMapByType = new LinkedHashMap<>();
        Arrays.stream(Operation.values()).forEach(
                value -> fieldsMapByType.put(value, new ArrayList<>()));

        TypeDefinitionRegistry overallRegistry = new TypeDefinitionRegistry();
        List<SDLDefinition<?>> allDefinitions = new ArrayList<>();

        for (DefinitionRegistry definitionRegistry : serviceRegistries) {
            collectTypes(fieldsMapByType, allDefinitions, definitionRegistry);
        }
        collectTypes(fieldsMapByType, allDefinitions, commonTypes);

        fieldsMapByType.keySet().forEach(key -> {
            List<FieldDefinition> fields = fieldsMapByType.get(key);
            if (fields.size() > 0) {
                overallRegistry.add(newObjectTypeDefinition()
                        .name(key.getDisplayName())
                        .sourceLocation(new SourceLocation(-1, -1, "generated"))
                        .fieldDefinitions(fields).build());
            }
        });

        // add our custom directives if they are not present
        if (!containsElement(allDefinitions, NadelDirectives.NADEL_HYDRATION_ARGUMENT_DEFINITION.getName(), InputObjectTypeDefinition.class)) {
            overallRegistry.add(NadelDirectives.NADEL_HYDRATION_ARGUMENT_DEFINITION);
        }
        if (!containsElement(allDefinitions, NadelDirectives.HYDRATED_DIRECTIVE_DEFINITION.getName(), DirectiveDefinition.class)) {
            overallRegistry.add(NadelDirectives.HYDRATED_DIRECTIVE_DEFINITION);
        }
        if (!containsElement(allDefinitions, NadelDirectives.RENAMED_DIRECTIVE_DEFINITION.getName(), DirectiveDefinition.class)) {
            overallRegistry.add(NadelDirectives.RENAMED_DIRECTIVE_DEFINITION);
        }

        for (SDLDefinition<?> definition : allDefinitions) {
            Optional<GraphQLError> error = overallRegistry.add(definition);
            if (error.isPresent()) {
                throw new GraphQLException("Unable to add definition to overall registry: " + error.get().getMessage());
            }
        }
        return overallRegistry;
    }

    private boolean containsElement(List<SDLDefinition<?>> allDefinitions, String name, Class<?> targetClass) {
        return allDefinitions.stream().anyMatch(sdlDef -> {
                    if (sdlDef instanceof NamedNode) {
                        String targetName = ((NamedNode<?>) sdlDef).getName();
                        if (targetName.equals(name)) {
                            Assert.assertTrue(sdlDef.getClass().equals(targetClass),
                                    () -> String.format("The element %s is expected to be a %s but is in fact a %s", name, targetClass, sdlDef.getClass()));
                            return true;
                        }
                    }
                    return false;
                }
        );
    }

    private void collectTypes(Map<Operation, List<FieldDefinition>> fieldsMapByType, List<SDLDefinition<?>> allDefinitions, DefinitionRegistry definitionRegistry) {
        Map<Operation, List<ObjectTypeDefinition>> opTypes = definitionRegistry.getOperationMap();
        Set<String> opTypeNames = new HashSet<>(3);

        opTypes.keySet().forEach(opType -> {
            List<ObjectTypeDefinition> opsDefinitions = opTypes.get(opType);
            if (opsDefinitions != null) {
                // Collect field definitions
                for (ObjectTypeDefinition objectTypeDefinition : opsDefinitions) {
                    fieldsMapByType.get(opType).addAll(objectTypeDefinition.getFieldDefinitions());
                }

                // Record down the type name for each operation
                String operationTypeName = definitionRegistry.getOperationTypeName(opType);
                if (operationTypeName != null) {
                    opTypeNames.add(operationTypeName);
                }
            }
        });

        definitionRegistry
                .getDefinitions()
                .stream()
                .filter(definition -> {
                    // Don't add operation types
                    if (definition instanceof ObjectTypeDefinition) {
                        ObjectTypeDefinition objectTypeDefinition = (ObjectTypeDefinition) definition;
                        return !opTypeNames.contains(objectTypeDefinition.getName());
                    }

                    return !(definition instanceof SchemaDefinition);
                })
                .forEach(allDefinitions::add);
    }

}
