// CVP MCP Server — Azure Container App deployment
// Deploy to existing Container Apps environment.
//
// Usage:
//   az deployment group create \
//     --resource-group YOUR_RESOURCE_GROUP \
//     --template-file mcp_server.bicep \
//     --parameters mcpBearerToken=<secret>

@description('Name for the MCP Server Container App')
param appName string = 'cvp-mcp-server'

@description('Azure region')
param location string = resourceGroup().location

@description('Container Apps Environment resource ID')
param containerAppsEnvironmentId string = '/subscriptions/YOUR_SUBSCRIPTION_ID/resourceGroups/YOUR_RESOURCE_GROUP/providers/Microsoft.App/managedEnvironments/YOUR_CONTAINER_APPS_ENV'

@description('Container Registry login server')
param containerRegistry string = 'YOUR_REGISTRY.azurecr.io'

@description('Image tag to deploy')
param imageTag string = 'latest'

@description('Bearer token for MCP server auth (stored as secret)')
@secure()
param mcpBearerToken string

@description('SQLite DB path inside container')
param dbPath string = '/data/cvp_demo.db'

resource mcpServerApp 'Microsoft.App/containerApps@2024-03-01' = {
  name: appName
  location: location
  identity: {
    type: 'SystemAssigned'
  }
  properties: {
    environmentId: containerAppsEnvironmentId
    configuration: {
      ingress: {
        external: true
        targetPort: 8001
        transport: 'http'
        corsPolicy: {
          allowedOrigins: ['*']
          allowedMethods: ['GET', 'POST', 'OPTIONS']
          allowedHeaders: ['Authorization', 'Content-Type']
        }
      }
      registries: [
        {
          server: containerRegistry
          identity: 'system'
        }
      ]
      secrets: [
        {
          name: 'mcp-bearer-token'
          value: mcpBearerToken
        }
      ]
    }
    template: {
      containers: [
        {
          name: 'mcp-server'
          image: '${containerRegistry}/cvp-mcp-server:${imageTag}'
          resources: {
            cpu: json('0.5')
            memory: '1Gi'
          }
          env: [
            {
              name: 'MCP_BEARER_TOKEN'
              secretRef: 'mcp-bearer-token'
            }
            {
              name: 'DB_PATH'
              value: dbPath
            }
          ]
          volumeMounts: [
            {
              volumeName: 'sqlite-data'
              mountPath: '/data'
            }
          ]
        }
      ]
      scale: {
        minReplicas: 1
        maxReplicas: 3
      }
      volumes: [
        {
          name: 'sqlite-data'
          storageType: 'EmptyDir'
        }
      ]
    }
  }
}

output mcpServerUrl string = 'https://${mcpServerApp.properties.configuration.ingress.fqdn}/mcp'
output appIdentityPrincipalId string = mcpServerApp.identity.principalId
