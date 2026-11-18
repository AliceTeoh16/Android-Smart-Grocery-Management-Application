# Smart Grocery Management Application - Backend Development Guide

This document outlines the detailed architecture, database design, and API contract specifications required to implement the backend services supporting the **Smart Application for Modern Grocery Management**, focusing particularly on the **AI Recipe Recommendation** and **Cloud Budget Sync** features.

---

## 1. System Architecture

The mobile application acts as the client which communicates with the backend server via HTTP REST APIs. To synchronize state and run heavier computational matching (like large-scale recipe search), the database layer is split:
*   **Frontend**: SQLite via Android Room Persistence Library (Offline-first, zero latency).
*   **Backend**: Relational database (PostgreSQL / MySQL) or Document store (MongoDB) to persist user profiles, recipes, and sync states.

```mermaid
graph LR
    subgraph Client (Mobile App)
        RoomDB[(Room SQLite)] <--> App[App Logic]
    end
    subgraph Server (Backend)
        API[API Endpoints] <--> DB[(PostgreSQL)]
    end
    App <-->|REST API / HTTP| API
```

---

## 2. Database Schema Design (SQL Representation)

To support grocery inventory, budget tracking, and the recipe catalog, the database should implement the following tables:

```sql
-- 1. Users Table
CREATE TABLE users (
    id SERIAL PRIMARY KEY,
    username VARCHAR(100) NOT NULL UNIQUE,
    email VARCHAR(150) NOT NULL UNIQUE,
    password_hash VARCHAR(255) NOT NULL,
    created_at TIMESTAMP DEFAULT CURRENT_TIMESTAMP
);

-- 2. Grocery Items Table (Sync target for Room DB)
CREATE TABLE grocery_items (
    id SERIAL PRIMARY KEY,
    user_id INT REFERENCES users(id) ON DELETE CASCADE,
    name VARCHAR(150) NOT NULL,
    quantity INT NOT NULL DEFAULT 1,
    price DECIMAL(10, 2) NOT NULL DEFAULT 0.00,
    category VARCHAR(100) NOT NULL,
    expiry_date TIMESTAMP,
    threshold INT NOT NULL DEFAULT 2,
    purchased_date TIMESTAMP NOT NULL DEFAULT CURRENT_TIMESTAMP
);

-- 3. Budget Limits Table
CREATE TABLE budget_entries (
    id SERIAL PRIMARY KEY,
    user_id INT REFERENCES users(id) ON DELETE CASCADE,
    limit_amount DECIMAL(10, 2) NOT NULL,
    month_year VARCHAR(7) NOT NULL, -- Format: "MM-YYYY"
    UNIQUE(user_id, month_year)
);

-- 4. Recipes Table
CREATE TABLE recipes (
    id SERIAL PRIMARY KEY,
    name VARCHAR(200) NOT NULL,
    image_url TEXT,
    video_url TEXT,
    instructions TEXT[] -- Array of cooking steps
);

-- 5. Recipe Ingredients Table (For matching relational join)
CREATE TABLE recipe_ingredients (
    id SERIAL PRIMARY KEY,
    recipe_id INT REFERENCES recipes(id) ON DELETE CASCADE,
    name VARCHAR(150) NOT NULL, -- e.g., "Egg", "Tomato"
    quantity_required DECIMAL(10, 2) NOT NULL,
    unit VARCHAR(50) NOT NULL -- e.g., "pcs", "g", "ml"
);
```

---

## 3. REST API Endpoint Specifications

All endpoint request and response bodies must use JSON.

### A. Inventory Sync
*   **Endpoint**: `POST /api/inventory/sync`
*   **Description**: Synchronizes local Room database records with the cloud database.
*   **Request Header**: `Authorization: Bearer <Token>`
*   **Request Body**:
    ```json
    {
      "items": [
        {
          "name": "Fresh Milk",
          "quantity": 2,
          "price": 3.50,
          "category": "Dairy",
          "expiryDate": 1785234500000,
          "threshold": 2,
          "purchasedDate": 1784934500000
        }
      ]
    }
    ```
*   **Response (200 OK)**:
    ```json
    {
      "status": "success",
      "message": "Successfully synchronized 1 items.",
      "lastSynced": 1784934600000
    }
    ```

---

### B. AI Recipe Recommendation
*   **Endpoint**: `GET /api/recipes/recommend`
*   **Description**: Scans the user's active inventory (stored in the cloud DB) and returns recommended dishes ranked by ingredient matching percentage.
*   **Request Header**: `Authorization: Bearer <Token>`
*   **Query Parameters** (Optional): `minMatchPercent=30` (defaults to 1)
*   **Response (200 OK)**:
    ```json
    {
      "recommendations": [
        {
          "recipe": {
            "id": 1,
            "name": "Tomato Scrambled Eggs",
            "imageUrl": "https://images.unsplash.com/photo-1598214813441-fd04166ecfc6?w=600",
            "videoUrl": "https://www.youtube.com/watch?v=1F_4-tGisw4",
            "instructions": [
              "Whisk the eggs in a bowl with a pinch of salt.",
              "Cut tomatoes into small wedges.",
              "Sauté tomatoes and mix back scrambled eggs."
            ]
          },
          "ownedIngredientsCount": 2,
          "totalIngredientsCount": 3,
          "missingIngredients": ["Scallion"],
          "matchPercentage": 66
        }
      ]
    }
    ```

---

### C. Budget Limits Sync
*   **Endpoint**: `POST /api/budget/sync`
*   **Description**: Syncs the current month's budget configurations.
*   **Request Header**: `Authorization: Bearer <Token>`
*   **Request Body**:
    ```json
    {
      "monthYear": "07-2026",
      "limitAmount": 300.00
    }
    ```
*   **Response (200 OK)**:
    ```json
    {
      "status": "success",
      "limitAmount": 300.00,
      "totalSpent": 120.50,
      "remaining": 179.50
    }
    ```

---

## 4. Backend Matching Engine Logic (Example Templates)

Here are code templates showing how to calculate the ingredient matching percentages on the server.

### Option A: Python (FastAPI + SQLAlchemy)

```python
from fastapi import FastAPI, Depends, HTTPException
from sqlalchemy.orm import Session
from typing import List, Dict

app = FastAPI()

def calculate_recipe_recommendations(user_id: int, db: Session):
    # 1. Get user's active ingredients (quantity > 0)
    active_items = db.query(GroceryItem).filter(
        GroceryItem.user_id == user_id,
        GroceryItem.quantity > 0
    ).all()
    
    # Store names in lowercase for fuzzy matching
    owned_ingredient_names = {item.name.lower() for item in active_items}
    
    # 2. Get all recipe templates with ingredients relation
    all_recipes = db.query(Recipe).all()
    recommendations = []
    
    for recipe in all_recipes:
        owned_count = 0
        missing = []
        
        for req_ing in recipe.ingredients:
            req_name_lower = req_ing.name.lower()
            
            # Match ingredient using basic substring checks
            has_match = any(
                (owned in req_name_lower or req_name_lower in owned)
                for owned in owned_ingredient_names
            )
            
            if has_match:
                owned_count += 1
            else:
                missing.append(req_ing.name)
                
        total_count = len(recipe.ingredients)
        if total_count == 0:
            continue
            
        match_percent = int((owned_count / total_count) * 100)
        
        # Only recommend if at least one ingredient matches
        if match_percent > 0:
            recommendations.append({
                "recipe": {
                    "id": recipe.id,
                    "name": recipe.name,
                    "imageUrl": recipe.image_url,
                    "videoUrl": recipe.video_url,
                    "instructions": recipe.instructions
                },
                "ownedIngredientsCount": owned_count,
                "totalIngredientsCount": total_count,
                "missingIngredients": missing,
                "matchPercentage": match_percent
            })
            
    # Sort recommendations by highest matching percentage descending
    recommendations.sort(key=lambda x: x["matchPercentage"], reverse=True)
    return recommendations
```

### Option B: Node.js (Express + Knex/SQL)

```javascript
const express = require('express');
const router = express.Router();

router.get('/recipes/recommend', async (req, res) => {
    const userId = req.userId; // Extracted from auth middleware
    
    try {
        // 1. Fetch user's active groceries
        const ownedItems = await db('grocery_items')
            .where({ user_id: userId })
            .andWhere('quantity', '>', 0);
            
        const ownedNames = ownedItems.map(item => item.name.toLowerCase());
        
        // 2. Fetch all recipes and ingredients
        const recipes = await db('recipes').select('*');
        const recommendations = [];
        
        for (const recipe of recipes) {
            const reqIngredients = await db('recipe_ingredients')
                .where({ recipe_id: recipe.id });
                
            let ownedCount = 0;
            const missing = [];
            
            for (const reqIng of reqIngredients) {
                const reqNameLower = reqIng.name.toLowerCase();
                const matched = ownedNames.some(owned => 
                    owned.includes(reqNameLower) || reqNameLower.includes(owned)
                );
                
                if (matched) {
                    ownedCount++;
                } else {
                    missing.push(reqIng.name);
                }
            }
            
            const totalCount = reqIngredients.length;
            if (totalCount === 0) continue;
            
            const matchPercent = Math.round((ownedCount / totalCount) * 100);
            
            if (matchPercent > 0) {
                recommendations.push({
                    recipe: {
                        id: recipe.id,
                        name: recipe.name,
                        imageUrl: recipe.image_url,
                        videoUrl: recipe.video_url,
                        instructions: recipe.instructions
                    },
                    ownedIngredientsCount: ownedCount,
                    totalIngredientsCount: totalCount,
                    missingIngredients: missing,
                    matchPercentage: matchPercent
                });
            }
        }
        
        // Sort by match percentage descending
        recommendations.sort((a, b) => b.matchPercentage - a.matchPercentage);
        
        res.json({ recommendations });
    } catch (err) {
        res.status(500).json({ error: "Failed to load recommendations." });
    }
});
```
